package nl.rijksoverheid.moz.nmc.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class HashHelperTest {

    private HashHelper hashHelper;

    @BeforeEach
    void setUp() {
        hashHelper = new HashHelper(Optional.of("test-pepper"));
    }

    @Test
    void hashIdentifier_null_retourneertNull() {
        assertNull(hashHelper.hashIdentifier(null));
    }

    @Test
    void hashIdentifier_zelfdeInput_levertZelfdeHashOp() {
        assertEquals(hashHelper.hashIdentifier("12345678"), hashHelper.hashIdentifier("12345678"));
    }

    @Test
    void hashIdentifier_verschillendeInput_levertVerschillendeHashOp() {
        assertNotEquals(hashHelper.hashIdentifier("12345678"), hashHelper.hashIdentifier("87654321"));
    }

    @Test
    void hashIdentifier_zelfdeInput_andereSecretPepper_levertAndereHashOp() {
        HashHelper anderePepper = new HashHelper(Optional.of("ander-pepper"));

        assertNotEquals(hashHelper.hashIdentifier("12345678"), anderePepper.hashIdentifier("12345678"));
    }

    @Test
    void hmac_zelfdeDoelEnWaarde_levertDezelfde32Bytes() {
        assertArrayEquals(hashHelper.hmac("doel-a", "waarde"), hashHelper.hmac("doel-a", "waarde"));
        assertEquals(32, hashHelper.hmac("doel-a", "waarde").length);
    }

    // Een HMAC voor het ene doel mag niet te koppelen zijn aan die voor een ander doel.
    @Test
    void hmac_anderDoelAndereWaardeOfAnderePepper_levertIetsAnders() {
        byte[] hmac = hashHelper.hmac("doel-a", "waarde");

        assertFalse(Arrays.equals(hmac, hashHelper.hmac("doel-b", "waarde")), "ander doel");
        assertFalse(Arrays.equals(hmac, hashHelper.hmac("doel-a", "andere waarde")), "andere waarde");
        assertFalse(
                Arrays.equals(hmac, new HashHelper(Optional.of("ander-pepper")).hmac("doel-a", "waarde")),
                "andere pepper");
    }

    @Test
    void constructor_ontbrekendePepper_gooitIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> new HashHelper(Optional.empty()));
    }

    @Test
    void constructor_blankePepper_gooitIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> new HashHelper(Optional.of("  ")));
    }
}
