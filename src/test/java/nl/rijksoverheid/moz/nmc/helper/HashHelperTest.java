package nl.rijksoverheid.moz.nmc.helper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void constructor_ontbrekendePepper_gooitIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> new HashHelper(Optional.empty()));
    }

    @Test
    void constructor_blankePepper_gooitIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> new HashHelper(Optional.of("  ")));
    }
}
