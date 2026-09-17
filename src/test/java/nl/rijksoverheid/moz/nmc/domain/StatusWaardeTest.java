package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusWaardeTest {

    // Regressietest: met de standaardlocale maakt toLowerCase() in een Turkse locale van de I een
    // dotless i, waardoor sending, delivered en de drie failure-waarden verminkt het CloudEvent naar
    // de Dienstverlener in gaan. Locale.ROOT in toApiValue voorkomt dat.
    @Test
    void toApiValue_inEenTurkseLocale_blijftOngewijzigd() {
        Locale oorspronkelijk = Locale.getDefault();
        try {
            Locale.setDefault(Locale.of("tr", "TR"));

            assertEquals("sending", StatusWaarde.SENDING.toApiValue());
            assertEquals("delivered", StatusWaarde.DELIVERED.toApiValue());
            assertEquals("technical-failure", StatusWaarde.TECHNICAL_FAILURE.toApiValue());
        } finally {
            Locale.setDefault(oorspronkelijk);
        }
    }

    // toApiValue() is de @JsonValue-serialisatie in de CloudEvent naar de Dienstverlener: een
    // regressie hier breekt consumenten stil, zonder dat enige andere test dit zou opmerken.
    @ParameterizedTest
    @CsvSource({
            "SENDING, sending",
            "DELIVERED, delivered",
            "PERMANENT_FAILURE, permanent-failure",
            "TEMPORARY_FAILURE, temporary-failure",
            "TECHNICAL_FAILURE, technical-failure",
            "CREATED, created",
            "ONBEKEND, onbekend"
    })
    void toApiValue_retourneertKebabCase(StatusWaarde status, String verwacht) {
        assertEquals(verwacht, status.toApiValue());
    }

    @ParameterizedTest
    @EnumSource(value = StatusWaarde.class,
            names = {"DELIVERED", "PERMANENT_FAILURE", "TEMPORARY_FAILURE", "TECHNICAL_FAILURE", "ONBEKEND"})
    void isDefinitief_terugmeldingenVanNotifyNl_retourneertTrue(StatusWaarde status) {
        assertTrue(status.isDefinitief());
    }

    @ParameterizedTest
    @EnumSource(value = StatusWaarde.class, names = {"CREATED", "SENDING"})
    void isDefinitief_verzendfase_retourneertFalse(StatusWaarde status) {
        assertFalse(status.isDefinitief());
    }

    @ParameterizedTest
    @CsvSource({
            "SENDING, CREATED",
            "ONBEKEND, SENDING",
            "DELIVERED, SENDING",
            "PERMANENT_FAILURE, SENDING",
            "TEMPORARY_FAILURE, CREATED",
            // NotifyNL kan ná een bezorging alsnog een fout melden, en andersom. Elke definitieve
            // status volgt daarom op elke andere definitieve status.
            "PERMANENT_FAILURE, DELIVERED",
            "TECHNICAL_FAILURE, DELIVERED",
            "DELIVERED, TEMPORARY_FAILURE",
            "DELIVERED, ONBEKEND",
            "ONBEKEND, DELIVERED"
    })
    void volgtOp_nieuweMelding_retourneertTrue(StatusWaarde nieuwe, StatusWaarde vastgelegd) {
        assertTrue(nieuwe.volgtOp(vastgelegd));
    }

    // De twee gevallen die geen nieuws zijn: precies dezelfde receipt (NotifyNL herhaalt bij elke
    // niet-2xx), en een melding die terugvalt naar de verzendfase.
    @ParameterizedTest
    @CsvSource({
            "DELIVERED, DELIVERED",
            "PERMANENT_FAILURE, PERMANENT_FAILURE",
            "SENDING, SENDING",
            "CREATED, CREATED",
            "SENDING, DELIVERED",
            "SENDING, PERMANENT_FAILURE",
            "CREATED, SENDING",
            "CREATED, DELIVERED"
    })
    void volgtOp_herhalingOfTerugval_retourneertFalse(StatusWaarde nieuwe, StatusWaarde vastgelegd) {
        assertFalse(nieuwe.volgtOp(vastgelegd));
    }

    // De indeling zoals hij hoort te zijn, expliciet opgeschreven in plaats van afgeleid uit de
    // productiecode. Binnen VERZENDFASE geldt de volgorde van de lijst; een TERUGMELDING volgt op
    // alles behalve zichzelf.
    private static final List<StatusWaarde> VERZENDFASE =
            List.of(StatusWaarde.CREATED, StatusWaarde.SENDING);
    private static final List<StatusWaarde> TERUGMELDINGEN = List.of(
            StatusWaarde.DELIVERED, StatusWaarde.PERMANENT_FAILURE, StatusWaarde.TEMPORARY_FAILURE,
            StatusWaarde.TECHNICAL_FAILURE, StatusWaarde.ONBEKEND);

    // Het volledige cartesisch product: alle 49 paren, niet de 18 die los zijn opgeschreven.
    @ParameterizedTest
    @MethodSource("alleParen")
    void volgtOp_overDeHeleMatrix_volgtDeVastgelegdeIndeling(StatusWaarde nieuwe, StatusWaarde vastgelegd) {
        boolean verwacht = verwachtVolgtOp(nieuwe, vastgelegd);

        assertEquals(verwacht, nieuwe.volgtOp(vastgelegd),
                nieuwe + ".volgtOp(" + vastgelegd + ") hoort " + verwacht + " te zijn");
    }

    // Bewaakt dat de twee lijsten samen elke constante noemen: een nieuwe StatusWaarde die in geen
    // van beide staat zou de matrixtest anders stilzwijgend overslaan.
    @Test
    void indeling_noemtElkeStatusWaarde() {
        assertEquals(StatusWaarde.values().length, VERZENDFASE.size() + TERUGMELDINGEN.size());
    }

    // De kern van de regel, apart vastgelegd omdat hij bewust afwijkt van wat "definitief" suggereert:
    // op een definitieve status volgt elke andere definitieve status. Wie die uitkomst uiteindelijk
    // laat winnen, is de afhandeling van de statussen zelf en hoort niet hier.
    @ParameterizedTest
    @MethodSource("alleParen")
    void volgtOp_naEenDefinitieveStatus_elkeAndereDefinitieveStatus(StatusWaarde nieuwe, StatusWaarde vastgelegd) {
        if (!vastgelegd.isDefinitief()) {
            return;
        }

        assertEquals(nieuwe.isDefinitief() && nieuwe != vastgelegd, nieuwe.volgtOp(vastgelegd),
                nieuwe + " na de definitieve status " + vastgelegd);
    }

    static Stream<Arguments> alleParen() {
        return Arrays.stream(StatusWaarde.values())
                .flatMap(nieuwe -> Arrays.stream(StatusWaarde.values())
                        .map(vastgelegd -> Arguments.of(nieuwe, vastgelegd)));
    }

    private static boolean verwachtVolgtOp(StatusWaarde nieuwe, StatusWaarde vastgelegd) {
        if (nieuwe == vastgelegd) {
            return false;
        }

        if (TERUGMELDINGEN.contains(nieuwe)) {
            return true;
        }

        return VERZENDFASE.contains(vastgelegd)
                && VERZENDFASE.indexOf(nieuwe) > VERZENDFASE.indexOf(vastgelegd);
    }
}
