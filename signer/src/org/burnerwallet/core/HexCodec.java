package org.burnerwallet.core;

/**
 * Hexadecimal encoding and decoding utilities.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class HexCodec {

    private static final char[] HEX_CHARS = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    /**
     * Encode a byte array to a lowercase hex string.
     */
    public static String encode(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = HEX_CHARS[v >>> 4];
            out[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * Decode a hex string to a byte array.
     * Accepts both upper-case and lower-case hex digits.
     *
     * @throws CryptoError if the string has odd length or contains invalid characters
     */
    public static byte[] decode(String hex) throws CryptoError {
        int len = hex.length();
        if (len % 2 != 0) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "Hex string must have even length, got " + len);
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = hexDigit(hex.charAt(i));
            int lo = hexDigit(hex.charAt(i + 1));
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexDigit(char c) throws CryptoError {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return 10 + (c - 'a');
        }
        if (c >= 'A' && c <= 'F') {
            return 10 + (c - 'A');
        }
        throw new CryptoError(CryptoError.ERR_ENCODING,
            "Invalid hex character: " + c);
    }
}
