package nl.rijksoverheid.moz.nmc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.VersleuteldeGegevens;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Versleutelt ontvanger en personalisation van een notificatie met een eigen AES-256-GCM-sleutel per
 * notificatie. Die sleutel wordt met de huidige KEK gewrapt, ook met AES-256-GCM, en samen met de
 * KEK-versie op de rij bewaard. Het wissen van de gewrapte sleutel maakt de gegevens onleesbaar.
 * <p>
 * Ciphertext is {@code IV (12 bytes) || versleutelde gegevens || tag (16 bytes)}. Als associated data
 * gaan de veldnaam en de notificatie-id mee ({@code ontvanger:<id>}, {@code personalisation:<id>}), bij de
 * gewrapte sleutel ook de KEK-versie ({@code sleutel:<kek_versie>:<id>}). Zo gaat een waarde niet open in
 * een ander veld, op een andere rij of onder een andere KEK-versie.
 */
@ApplicationScoped
public class Sleutelbeheer {

    private static final String GCM = "AES/GCM/NoPadding";
    private static final int SLEUTEL_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final TypeReference<Map<String, String>> PERSONALISATION_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Ontvanger> ONTVANGER_TYPE = new TypeReference<>() {
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
    public VersleuteldeGegevens versleutel(UUID notificatieId, Ontvanger ontvanger, Map<String, String> personalisation) {
        Objects.requireNonNull(notificatieId, "notificatieId is verplicht");
        Objects.requireNonNull(ontvanger, "ontvanger is verplicht");

        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(SLEUTEL_BITS, random);
            SecretKey sleutel = generator.generateKey();

            int kekVersie = kekProvider.huidigeVersie();

            return new VersleuteldeGegevens(
                    versleutelVeld(sleutel, aad("ontvanger", notificatieId), serialiseer(ontvanger)),
                    versleutelVeld(sleutel, aad("personalisation", notificatieId), serialiseer(personalisatieOfLeeg(personalisation))),
                    wrap(notificatieId, sleutel, kekVersie),
                    kekVersie);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Versleutelen van notificatiegegevens mislukt", e);
        }
    }

    public Ontvanger ontsleutelOntvanger(UUID notificatieId, VersleuteldeGegevens gegevens) {
        byte[] json = ontsleutelVeld(pakUit(notificatieId, gegevens), aad("ontvanger", notificatieId),
                gegevens.ontvangerVersleuteld());

        return leesJson(json, ONTVANGER_TYPE, "ontvanger");
    }

    public Map<String, String> ontsleutelPersonalisation(UUID notificatieId, VersleuteldeGegevens gegevens) {
        byte[] json = ontsleutelVeld(pakUit(notificatieId, gegevens), aad("personalisation", notificatieId),
                gegevens.personalisationVersleuteld());

        return leesJson(json, PERSONALISATION_TYPE, "personalisation");
    }

    byte[] wrap(UUID notificatieId, SecretKey sleutel, int kekVersie) throws GeneralSecurityException {
        return versleutelVeld(kek(kekVersie), aadSleutel(kekVersie, notificatieId), sleutel.getEncoded());
    }

    SecretKey pakUit(UUID notificatieId, VersleuteldeGegevens gegevens) {
        Objects.requireNonNull(notificatieId, "notificatieId is verplicht");
        Objects.requireNonNull(gegevens, "gegevens is verplicht");

        if (gegevens.sleutelGewrapt() == null) {
            throw new SleutelGewistException("De notificatie heeft geen sleutel; de gegevens zijn gewist of nooit vastgelegd");
        }

        if (gegevens.kekVersie() == null) {
            throw new OntsleutelenMisluktException("De notificatie heeft een gewrapte sleutel zonder KEK-versie");
        }

        int kekVersie = gegevens.kekVersie();
        SecretKey kek = kek(kekVersie);

        try {
            return new SecretKeySpec(ontsleutelVeld(kek, aadSleutel(kekVersie, notificatieId), gegevens.sleutelGewrapt()), "AES");
        } catch (OntsleutelenMisluktException e) {
            throw new OntsleutelenMisluktException("Gewrapte sleutel past niet bij KEK-versie " + kekVersie, e);
        }
    }

    private static byte[] aad(String veld, UUID notificatieId) {
        return (veld + ":" + notificatieId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] aadSleutel(int kekVersie, UUID notificatieId) {
        return ("sleutel:" + kekVersie + ":" + notificatieId).getBytes(StandardCharsets.UTF_8);
    }

    // Zonder de Jackson-fout als oorzaak: de melding daarvan citeert de ontsleutelde tekst.
    private <T> T leesJson(byte[] json, TypeReference<T> type, String veld) {
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            throw new OntsleutelenMisluktException("Ontsleutelde " + veld + " is geen geldige JSON");
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

    private static Map<String, String> personalisatieOfLeeg(Map<String, String> personalisation) {
        return personalisation != null ? personalisation : Map.of();
    }

    // Zonder de Jackson-fout als oorzaak, om dezelfde reden als bij het lezen.
    private byte[] serialiseer(Object waarde) {
        try {
            return objectMapper.writeValueAsBytes(waarde);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Te versleutelen waarde kon niet naar JSON");
        }
    }
}
