package nl.rijksoverheid.moz.nmc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.VersleuteldeGegevens;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Versleutelt ontvanger en personalisation van een notificatie met een eigen AES-256-GCM-sleutel per
 * notificatie. Die sleutel wordt met de huidige KEK gewrapt (AES Key Wrap, RFC 3394) en samen met de
 * KEK-versie op de rij bewaard. Het wissen van de gewrapte sleutel maakt de gegevens onleesbaar.
 * <p>
 * Ciphertext is {@code IV (12 bytes) || versleutelde gegevens || tag (16 bytes)}. De veldnaam gaat als
 * associated data mee, zodat ontvanger en personalisation niet onderling verwisseld kunnen worden.
 */
@ApplicationScoped
public class Sleutelbeheer {

    private static final String GCM = "AES/GCM/NoPadding";
    private static final String KEY_WRAP = "AESWrap";
    private static final int SLEUTEL_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD_ONTVANGER = "ontvanger".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_PERSONALISATION = "personalisation".getBytes(StandardCharsets.UTF_8);
    private static final TypeReference<Map<String, String>> PERSONALISATION_TYPE = new TypeReference<>() {
    };

    private final KekProvider kekProvider;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public Sleutelbeheer(KekProvider kekProvider, ObjectMapper objectMapper) {
        this.kekProvider = kekProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * @param personalisation null wordt als een lege map versleuteld; NotifyNL krijgt in beide gevallen
     *        een lege personalisation
     */
    public VersleuteldeGegevens versleutel(String ontvanger, Map<String, String> personalisation) {
        Objects.requireNonNull(ontvanger, "ontvanger is verplicht");

        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(SLEUTEL_BITS, random);
            SecretKey sleutel = generator.generateKey();

            int kekVersie = kekProvider.huidigeVersie();
            Cipher wrap = Cipher.getInstance(KEY_WRAP);
            wrap.init(Cipher.WRAP_MODE, kek(kekVersie));

            return new VersleuteldeGegevens(
                    versleutelVeld(sleutel, AAD_ONTVANGER, ontvanger.getBytes(StandardCharsets.UTF_8)),
                    versleutelVeld(sleutel, AAD_PERSONALISATION, serialiseer(personalisation)),
                    wrap.wrap(sleutel),
                    kekVersie);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Versleutelen van notificatiegegevens mislukt", e);
        }
    }

    public String ontsleutelOntvanger(VersleuteldeGegevens gegevens) {
        byte[] ontvanger = ontsleutelVeld(pakUit(gegevens), AAD_ONTVANGER, gegevens.ontvangerVersleuteld());

        return new String(ontvanger, StandardCharsets.UTF_8);
    }

    public Map<String, String> ontsleutelPersonalisation(VersleuteldeGegevens gegevens) {
        byte[] json = ontsleutelVeld(pakUit(gegevens), AAD_PERSONALISATION, gegevens.personalisationVersleuteld());

        try {
            return objectMapper.readValue(json, PERSONALISATION_TYPE);
        } catch (IOException e) {
            throw new OntsleutelenMisluktException("Ontsleutelde personalisation is geen geldige JSON", e);
        }
    }

    private SecretKey pakUit(VersleuteldeGegevens gegevens) {
        Objects.requireNonNull(gegevens, "gegevens is verplicht");

        if (gegevens.sleutelGewrapt() == null) {
            throw new SleutelGewistException("De notificatie heeft geen sleutel; de gegevens zijn gewist of nooit vastgelegd");
        }

        if (gegevens.kekVersie() == null) {
            throw new OntsleutelenMisluktException("De notificatie heeft een gewrapte sleutel zonder KEK-versie");
        }

        try {
            Cipher unwrap = Cipher.getInstance(KEY_WRAP);
            unwrap.init(Cipher.UNWRAP_MODE, kek(gegevens.kekVersie()));

            return (SecretKey) unwrap.unwrap(gegevens.sleutelGewrapt(), "AES", Cipher.SECRET_KEY);
        } catch (InvalidKeyException e) {
            throw new OntsleutelenMisluktException("Gewrapte sleutel past niet bij KEK-versie " + gegevens.kekVersie(), e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Uitpakken van de sleutel mislukt", e);
        }
    }

    private SecretKey kek(int versie) {
        return kekProvider.kek(versie)
                .orElseThrow(() -> new OntsleutelenMisluktException("KEK-versie " + versie + " is niet geconfigureerd"));
    }

    private byte[] versleutelVeld(SecretKey sleutel, byte[] aad, byte[] platteTekst) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(GCM);
        cipher.init(Cipher.ENCRYPT_MODE, sleutel, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad);
        byte[] versleuteld = cipher.doFinal(platteTekst);

        byte[] resultaat = Arrays.copyOf(iv, IV_BYTES + versleuteld.length);
        System.arraycopy(versleuteld, 0, resultaat, IV_BYTES, versleuteld.length);

        return resultaat;
    }

    private byte[] ontsleutelVeld(SecretKey sleutel, byte[] aad, byte[] versleuteld) {
        if (versleuteld == null || versleuteld.length < IV_BYTES + TAG_BITS / 8) {
            throw new OntsleutelenMisluktException("Versleutelde waarde ontbreekt of is te kort");
        }

        try {
            Cipher cipher = Cipher.getInstance(GCM);
            cipher.init(Cipher.DECRYPT_MODE, sleutel, new GCMParameterSpec(TAG_BITS, versleuteld, 0, IV_BYTES));
            cipher.updateAAD(aad);

            return cipher.doFinal(versleuteld, IV_BYTES, versleuteld.length - IV_BYTES);
        } catch (AEADBadTagException e) {
            throw new OntsleutelenMisluktException("Versleutelde waarde is gewijzigd of hoort niet bij deze sleutel", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ontsleutelen mislukt", e);
        }
    }

    private byte[] serialiseer(Map<String, String> personalisation) {
        try {
            return objectMapper.writeValueAsBytes(personalisation != null ? personalisation : Map.of());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Personalisation kon niet naar JSON", e);
        }
    }
}
