package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for Base58 — standard Base58 encoding/decoding
 * and Base58Check encoding/decoding with checksum verification.
 */
public class Base58Test {

    // ---- Base58 encode tests ----

    @Test
    public void encodeEmptyBytes() {
        assertEquals("", Base58.encode(new byte[0]));
    }

    @Test
    public void encodeLeadingZerosSingle() {
        // A single zero byte encodes to a single '1'
        assertEquals("1", Base58.encode(new byte[]{0}));
    }

    @Test
    public void encodeLeadingZerosDouble() {
        // Two zero bytes encode to "11"
        assertEquals("11", Base58.encode(new byte[]{0, 0}));
    }

    @Test
    public void encodeDecodeRoundTrip() throws CryptoError {
        // Hex "0000010203" has two leading zero bytes
        byte[] input = HexCodec.decode("0000010203");
        String encoded = Base58.encode(input);
        byte[] decoded = Base58.decode(encoded);
        assertArrayEquals(input, decoded);
    }

    // ---- Base58 decode tests ----

    @Test
    public void decodeEmptyString() throws CryptoError {
        assertArrayEquals(new byte[0], Base58.decode(""));
    }

    @Test
    public void decodeLeadingOnes() throws CryptoError {
        // "1" decodes to a single zero byte
        assertArrayEquals(new byte[]{0}, Base58.decode("1"));
        // "11" decodes to two zero bytes
        assertArrayEquals(new byte[]{0, 0}, Base58.decode("11"));
    }

    @Test(expected = CryptoError.class)
    public void decodeInvalidCharacter() throws CryptoError {
        // '0' is not in the Base58 alphabet
        Base58.decode("0");
    }

    @Test(expected = CryptoError.class)
    public void decodeInvalidCharacterCapitalO() throws CryptoError {
        // 'O' is not in the Base58 alphabet
        Base58.decode("O");
    }

    @Test(expected = CryptoError.class)
    public void decodeInvalidCharacterCapitalI() throws CryptoError {
        // 'I' is not in the Base58 alphabet
        Base58.decode("I");
    }

    @Test(expected = CryptoError.class)
    public void decodeInvalidCharacterLowerL() throws CryptoError {
        // 'l' is not in the Base58 alphabet
        Base58.decode("l");
    }

    // ---- Base58Check encode/decode tests ----

    @Test
    public void encodeCheckDecodeCheckRoundTrip() throws CryptoError {
        byte[] input = HexCodec.decode("0000010203");
        String encoded = Base58.encodeCheck(input);
        byte[] decoded = Base58.decodeCheck(encoded);
        assertArrayEquals(input, decoded);
    }

    @Test
    public void decodeCheckBadChecksum() throws CryptoError {
        // Encode with valid checksum
        byte[] input = HexCodec.decode("0000010203");
        String encoded = Base58.encodeCheck(input);

        // Corrupt the last character to invalidate the checksum
        char lastChar = encoded.charAt(encoded.length() - 1);
        char corruptChar;
        if (lastChar == '1') {
            corruptChar = '2';
        } else {
            corruptChar = '1';
        }
        String corrupted = encoded.substring(0, encoded.length() - 1) + corruptChar;

        try {
            Base58.decodeCheck(corrupted);
            fail("Expected CryptoError for bad checksum");
        } catch (CryptoError e) {
            assertEquals(CryptoError.ERR_CHECKSUM, e.getErrorCode());
        }
    }

    // ---- Known BIP32 vector test ----

    @Test
    public void knownXprvPrefix() throws CryptoError {
        // BIP32 Test Vector 1 xprv
        String xprv = "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPP" +
            "qjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi";

        byte[] decoded = Base58.decodeCheck(xprv);

        // Should be 78 bytes (4 version + 1 depth + 4 fingerprint + 4 index
        // + 32 chaincode + 33 key)
        assertEquals(78, decoded.length);

        // First 4 bytes should be the xprv version: 0x0488ade4
        String versionHex = HexCodec.encode(ByteArrayUtils.copyOfRange(decoded, 0, 4));
        assertEquals("0488ade4", versionHex);
    }

    // ---- Additional round-trip tests with known vectors ----

    @Test
    public void encodeKnownVector() throws CryptoError {
        // Test with a known hex -> Base58 vector
        // Hex "61" = ASCII 'a' = Base58 "2g"
        byte[] input = HexCodec.decode("61");
        assertEquals("2g", Base58.encode(input));
    }

    @Test
    public void roundTripSingleByte() throws CryptoError {
        for (int i = 0; i < 256; i++) {
            byte[] input = new byte[]{(byte) i};
            String encoded = Base58.encode(input);
            byte[] decoded = Base58.decode(encoded);
            assertArrayEquals("Failed for byte value " + i, input, decoded);
        }
    }
}
