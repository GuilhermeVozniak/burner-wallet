package org.burnerwallet.core;

/**
 * Base58 and Base58Check encoding/decoding.
 *
 * Uses the Bitcoin Base58 alphabet:
 * {@code 123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz}
 *
 * Base58Check appends a 4-byte double-SHA-256 checksum before encoding,
 * and verifies it on decode.
 *
 * Arithmetic is done on raw byte arrays to avoid dependency on
 * java.math.BigInteger, which is not available in CLDC 1.1.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class Base58 {

    private static final char[] ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    /**
     * Reverse lookup table: ASCII code point -> Base58 digit value.
     * -1 means the character is not in the alphabet.
     */
    private static final int[] INDEXES = new int[128];
    static {
        for (int i = 0; i < INDEXES.length; i++) {
            INDEXES[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            INDEXES[ALPHABET[i]] = i;
        }
    }

    /**
     * Encode a byte array to a Base58 string.
     * Leading zero bytes in the input map to leading '1' characters.
     *
     * @param input data to encode
     * @return Base58-encoded string, or "" for empty input
     */
    public static String encode(byte[] input) {
        if (input.length == 0) {
            return "";
        }

        // Count leading zero bytes
        int leadingZeros = 0;
        while (leadingZeros < input.length && input[leadingZeros] == 0) {
            leadingZeros++;
        }

        // Make a mutable copy of the input for in-place division
        byte[] number = new byte[input.length];
        System.arraycopy(input, 0, number, 0, input.length);

        // Worst case: each byte expands to ~1.37 Base58 digits (log(256)/log(58))
        // Use input.length * 2 as safe upper bound
        char[] encoded = new char[input.length * 2];
        int outputStart = encoded.length;

        int start = leadingZeros;
        while (start < number.length) {
            // Divide number[] by 58, get remainder
            int remainder = divmod(number, start, 58);
            // If the leading byte is now zero, skip it next time
            if (number[start] == 0) {
                start++;
            }
            encoded[--outputStart] = ALPHABET[remainder];
        }

        // Prepend '1' for each leading zero byte in original input
        for (int i = 0; i < leadingZeros; i++) {
            encoded[--outputStart] = '1';
        }

        return new String(encoded, outputStart, encoded.length - outputStart);
    }

    /**
     * Decode a Base58 string to a byte array.
     * Leading '1' characters map to leading zero bytes.
     *
     * @param input Base58-encoded string
     * @return decoded byte array
     * @throws CryptoError if the string contains invalid characters
     */
    public static byte[] decode(String input) throws CryptoError {
        if (input.length() == 0) {
            return new byte[0];
        }

        // Count leading '1' characters (they represent leading zero bytes)
        int leadingOnes = 0;
        while (leadingOnes < input.length() && input.charAt(leadingOnes) == '1') {
            leadingOnes++;
        }

        // Convert the Base58 digits to a base-256 (byte) number.
        // Size estimate: each Base58 char is ~0.73 bytes (log(58)/log(256))
        // Use input.length() as safe upper bound.
        int inputLen = input.length();
        byte[] b256 = new byte[inputLen];
        int outputStart = b256.length;

        for (int i = leadingOnes; i < inputLen; i++) {
            char c = input.charAt(i);
            int digit = -1;
            if (c >= 0 && c < 128) {
                digit = INDEXES[c];
            }
            if (digit < 0) {
                throw new CryptoError(CryptoError.ERR_ENCODING,
                    "Invalid Base58 character: " + c);
            }

            // Multiply existing b256 number by 58 and add digit
            int carry = digit;
            for (int j = b256.length - 1; j >= outputStart; j--) {
                carry += (b256[j] & 0xFF) * 58;
                b256[j] = (byte) (carry & 0xFF);
                carry = carry >>> 8;
            }
            // Extend with carry bytes
            while (carry > 0) {
                b256[--outputStart] = (byte) (carry & 0xFF);
                carry = carry >>> 8;
            }
        }

        // Build result: leading zeros + converted bytes
        int numBytes = b256.length - outputStart;
        byte[] result = new byte[leadingOnes + numBytes];
        // First leadingOnes bytes are already zero from array init
        System.arraycopy(b256, outputStart, result, leadingOnes, numBytes);

        return result;
    }

    /**
     * Encode a byte array with a 4-byte Base58Check checksum.
     * Appends the first 4 bytes of doubleSha256(payload), then Base58 encodes.
     *
     * @param payload data to encode
     * @return Base58Check-encoded string
     */
    public static String encodeCheck(byte[] payload) {
        byte[] checksum = HashUtils.doubleSha256(payload);
        byte[] checksumBytes = ByteArrayUtils.copyOfRange(checksum, 0, 4);
        byte[] data = ByteArrayUtils.concat(payload, checksumBytes);
        return encode(data);
    }

    /**
     * Decode a Base58Check string, verifying the 4-byte checksum.
     *
     * @param input Base58Check-encoded string
     * @return decoded payload (without checksum)
     * @throws CryptoError if the string contains invalid characters or
     *                      the checksum does not match
     */
    public static byte[] decodeCheck(String input) throws CryptoError {
        byte[] data = decode(input);

        if (data.length < 4) {
            throw new CryptoError(CryptoError.ERR_CHECKSUM,
                "Base58Check input too short for checksum");
        }

        // Split into payload and checksum
        byte[] payload = ByteArrayUtils.copyOfRange(data, 0, data.length - 4);
        byte[] checksum = ByteArrayUtils.copyOfRange(data, data.length - 4, data.length);

        // Verify checksum
        byte[] expectedChecksum = ByteArrayUtils.copyOfRange(
            HashUtils.doubleSha256(payload), 0, 4);

        if (!ByteArrayUtils.constantTimeEquals(checksum, expectedChecksum)) {
            throw new CryptoError(CryptoError.ERR_CHECKSUM,
                "Base58Check checksum mismatch");
        }

        return payload;
    }

    /**
     * Divide the number represented by {@code number} (big-endian unsigned)
     * by {@code divisor} in place, returning the remainder.
     *
     * @param number   big-endian unsigned integer (modified in place)
     * @param start    index of the first non-zero byte
     * @param divisor  the divisor (must be <= 256 to avoid int overflow)
     * @return remainder of the division
     */
    private static int divmod(byte[] number, int start, int divisor) {
        int remainder = 0;
        for (int i = start; i < number.length; i++) {
            int digit = (number[i] & 0xFF) + remainder * 256;
            number[i] = (byte) (digit / divisor);
            remainder = digit % divisor;
        }
        return remainder;
    }
}
