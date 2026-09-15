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
            names = {"DELIVERED", "PERMANENT_FAILURE", "TEMPORARY_FAILURE", "TECHNICAL_FAILURE"})
    void isDefinitief_eindstatussenVanNotifyNl_retourneertTrue(StatusWaarde status) {
        assertTrue(status.isDefinitief());
    }

    @ParameterizedTest
    @EnumSource(value = StatusWaarde.class, names = {"CREATED", "SENDING", "ONBEKEND"})
    void isDefinitief_statussenWaarNietZekerIsDatErGeenVervolgcallbackKomt_retourneertFalse(StatusWaarde status) {
        assertFalse(status.isDefinitief());
    }

    @ParameterizedTest
    @CsvSource({
            "SENDING, CREATED",
            "ONBEKEND, SENDING",
            "DELIVERED, SENDING",
            "PERMANENT_FAILURE, SENDING",
            "TEMPORARY_FAILURE, ONBEKEND",
            "DELIVERED, ONBEKEND",
            // Een bezorging die alsnog wordt teruggemeld ná een faalstatus is wél nieuws: bezorgd is
            // de uitkomst, ook als NotifyNL eerder iets anders meldde.
            "DELIVERED, TEMPORARY_FAILURE",
            "DELIVERED, PERMANENT_FAILURE",
            "DELIVERED, TECHNICAL_FAILURE"
    })
    void volgtOp_hogereRang_retourneertTrue(StatusWaarde nieuwe, StatusWaarde vastgelegd) {
        assertTrue(nieuwe.volgtOp(vastgelegd));
    }

    // De kern van de regel: een dubbele of laat aangekomen receipt mag een vastgelegde uitkomst niet
    // terugdraaien. NotifyNL herhaalt bij elke niet-2xx, dus dit is geen theoretisch geval.
    @ParameterizedTest
    @CsvSource({
            // Een late faalstatus ná DELIVERED — dit is het geval dat de oude isDefinitief-controle
            // doorliet, omdat beide statussen definitief zijn.
            "TEMPORARY_FAILURE, DELIVERED",
            "PERMANENT_FAILURE, DELIVERED",
            "TECHNICAL_FAILURE, DELIVERED",
            // Een herhaling van precies dezelfde receipt.
            "DELIVERED, DELIVERED",
            "PERMANENT_FAILURE, PERMANENT_FAILURE",
            // Een tweede, afwijkende eindstatus voor dezelfde verzending: de eerste blijft staan.
            "PERMANENT_FAILURE, TEMPORARY_FAILURE",
            "TEMPORARY_FAILURE, TECHNICAL_FAILURE",
            // Terug naar een niet-definitieve status.
            "SENDING, DELIVERED",
            "SENDING, PERMANENT_FAILURE",
            "CREATED, SENDING",
            // Een status die de NMC niet kent mag een bekende uitkomst nooit overschrijven.
            "ONBEKEND, DELIVERED",
            "ONBEKEND, PERMANENT_FAILURE"
    })
    void volgtOp_gelijkeOfLagereRang_retourneertFalse(StatusWaarde nieuwe, StatusWaarde vastgelegd) {
        assertFalse(nieuwe.volgtOp(vastgelegd));
    }

    // De rangorde zoals hij hoort te zijn, expliciet opgeschreven in plaats van afgeleid uit de
    // productiecode. rang() is een switch zonder default, dus de compiler dwingt af dát een nieuwe
    // status een rang krijgt — niet wélke. Een status die per ongeluk in de DELIVERED-tak belandt kan
    // een bezorging overschrijven zonder dat er iets valt; deze lijst plus de matrix hieronder vangen
    // dat, en blijven kloppen als er een status bijkomt (de matrix dekt dan automatisch mee).
    private static final List<List<StatusWaarde>> RANGORDE = List.of(
            List.of(StatusWaarde.CREATED),
            List.of(StatusWaarde.SENDING),
            List.of(StatusWaarde.ONBEKEND),
            List.of(StatusWaarde.TEMPORARY_FAILURE, StatusWaarde.TECHNICAL_FAILURE, StatusWaarde.PERMANENT_FAILURE),
            List.of(StatusWaarde.DELIVERED));

    // Het volledige cartesisch product: alle 49 paren, niet de 21 die los waren opgeschreven.
    @ParameterizedTest
    @MethodSource("alleParen")
    void volgtOp_overDeHeleMatrix_volgtDeVastgelegdeRangorde(StatusWaarde nieuwe, StatusWaarde vastgelegd) {
        boolean verwacht = rang(nieuwe) > rang(vastgelegd);

        assertEquals(verwacht, nieuwe.volgtOp(vastgelegd),
                nieuwe + ".volgtOp(" + vastgelegd + ") hoort " + verwacht + " te zijn");
    }

    // Bewaakt dat RANGORDE hierboven elke constante noemt: een nieuwe StatusWaarde die niemand in de
    // lijst zet zou de matrixtest anders stilzwijgend overslaan.
    @Test
    void rangorde_noemtElkeStatusWaarde() {
        assertEquals(StatusWaarde.values().length,
                RANGORDE.stream().mapToLong(List::size).sum());
    }

    static Stream<Arguments> alleParen() {
        return Arrays.stream(StatusWaarde.values())
                .flatMap(nieuwe -> Arrays.stream(StatusWaarde.values())
                        .map(vastgelegd -> Arguments.of(nieuwe, vastgelegd)));
    }

    private static int rang(StatusWaarde status) {
        return RANGORDE.stream()
                .filter(groep -> groep.contains(status))
                .mapToInt(RANGORDE::indexOf)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Status " + status + " ontbreekt in RANGORDE"));
    }
}
