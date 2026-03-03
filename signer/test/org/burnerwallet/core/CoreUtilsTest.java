package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for CryptoError, HexCodec, and ByteArrayUtils.
 */
public class CoreUtilsTest {

    // ---- HexCodec tests ----

    @Test
    public void hexEncodeEmpty() {
        assertEquals("", HexCodec.encode(new byte[0]));
    }

    @Test
    public void hexEncodeBytes() {
        byte[] input = new byte[] {
            (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef
        };
        assertEquals("deadbeef", HexCodec.encode(input));
    }

    @Test
    public void hexDecodeRoundTrip() throws CryptoError {
        String hex = "0123456789abcdef";
        byte[] decoded = HexCodec.decode(hex);
        String reEncoded = HexCodec.encode(decoded);
        assertEquals(hex, reEncoded);
    }

    @Test
    public void hexDecodeUpperCase() throws CryptoError {
        // decode should accept upper-case hex and produce same bytes
        byte[] lower = HexCodec.decode("deadbeef");
        byte[] upper = HexCodec.decode("DEADBEEF");
        assertArrayEquals(lower, upper);
    }

    @Test(expected = CryptoError.class)
    public void hexDecodeOddLength() throws CryptoError {
        HexCodec.decode("abc");  // odd length -> CryptoError
    }

    @Test(expected = CryptoError.class)
    public void hexDecodeInvalidChars() throws CryptoError {
        HexCodec.decode("zzzz");  // invalid hex chars -> CryptoError
    }

    // ---- ByteArrayUtils tests ----

    @Test
    public void concatBytes() {
        byte[] a = new byte[] { 1, 2, 3 };
        byte[] b = new byte[] { 4, 5 };
        byte[] result = ByteArrayUtils.concat(a, b);
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5 }, result);
    }

    @Test
    public void concatEmptyLeft() {
        byte[] a = new byte[0];
        byte[] b = new byte[] { 4, 5 };
        byte[] result = ByteArrayUtils.concat(a, b);
        assertArrayEquals(new byte[] { 4, 5 }, result);
    }

    @Test
    public void concatEmptyRight() {
        byte[] a = new byte[] { 1, 2 };
        byte[] b = new byte[0];
        byte[] result = ByteArrayUtils.concat(a, b);
        assertArrayEquals(new byte[] { 1, 2 }, result);
    }

    @Test
    public void copyOfTruncates() {
        byte[] src = new byte[] { 1, 2, 3, 4, 5 };
        byte[] result = ByteArrayUtils.copyOf(src, 3);
        assertArrayEquals(new byte[] { 1, 2, 3 }, result);
    }

    @Test
    public void copyOfPads() {
        byte[] src = new byte[] { 1, 2 };
        byte[] result = ByteArrayUtils.copyOf(src, 5);
        assertArrayEquals(new byte[] { 1, 2, 0, 0, 0 }, result);
    }

    @Test
    public void copyOfRangeSubset() {
        byte[] src = new byte[] { 10, 20, 30, 40, 50 };
        byte[] result = ByteArrayUtils.copyOfRange(src, 1, 4);
        assertArrayEquals(new byte[] { 20, 30, 40 }, result);
    }

    @Test
    public void constantTimeEqualsTrue() {
        byte[] a = new byte[] { 1, 2, 3 };
        byte[] b = new byte[] { 1, 2, 3 };
        assertTrue(ByteArrayUtils.constantTimeEquals(a, b));
    }

    @Test
    public void constantTimeEqualsFalse() {
        byte[] a = new byte[] { 1, 2, 3 };
        byte[] b = new byte[] { 1, 2, 4 };
        assertFalse(ByteArrayUtils.constantTimeEquals(a, b));
    }

    @Test
    public void constantTimeEqualsDifferentLength() {
        byte[] a = new byte[] { 1, 2, 3 };
        byte[] b = new byte[] { 1, 2 };
        assertFalse(ByteArrayUtils.constantTimeEquals(a, b));
    }

    @Test
    public void zeroFill() {
        byte[] data = new byte[] { 1, 2, 3, 4, 5 };
        ByteArrayUtils.zeroFill(data);
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 0 }, data);
    }

    // ---- CryptoError tests ----

    @Test
    public void cryptoErrorCode() {
        CryptoError err = new CryptoError(CryptoError.ERR_INVALID_MNEMONIC, "bad mnemonic");
        assertEquals(CryptoError.ERR_INVALID_MNEMONIC, err.getErrorCode());
        assertEquals("bad mnemonic", err.getMessage());
    }

    @Test
    public void cryptoErrorConstants() {
        assertEquals(1, CryptoError.ERR_INVALID_MNEMONIC);
        assertEquals(2, CryptoError.ERR_INVALID_SEED_LENGTH);
        assertEquals(3, CryptoError.ERR_DERIVATION_FAILED);
        assertEquals(4, CryptoError.ERR_INVALID_KEY);
        assertEquals(5, CryptoError.ERR_ENCODING);
        assertEquals(6, CryptoError.ERR_CHECKSUM);
    }
}
