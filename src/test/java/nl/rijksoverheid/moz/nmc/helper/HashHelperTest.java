package nl.rijksoverheid.moz.nmc.helper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HashHelperTest {

    private HashHelper hashHelper;

    @BeforeEach
    void setUp() {
        hashHelper = new HashHelper();
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
}
