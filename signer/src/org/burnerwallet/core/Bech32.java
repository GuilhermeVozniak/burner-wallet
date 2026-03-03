package org.burnerwallet.core;

/**
 * BIP173 Bech32 encoding and decoding for segwit addresses.
 *
 * Reference: https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class Bech32 {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    /** Generator values for the Bech32 polymod checksum. */
    private static final int[] GENERATOR = {
        0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
    };

    /**
     * Encode a segwit address.
     *
     * @param hrp             human-readable part ("bc" for mainnet, "tb" for testnet)
     * @param witnessVersion  witness version (0..16)
     * @param witnessProgram  witness program bytes (2..40 bytes)
     * @return lowercase bech32 address string
     * @throws CryptoError if parameters are invalid
     */
    public static String encode(String hrp, int witnessVersion, byte[] witnessProgram)
            throws CryptoError {
        if (witnessVersion < 0 || witnessVersion > 16) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "Invalid witness version: " + witnessVersion);
        }
        if (witnessProgram.length < 2 || witnessProgram.length > 40) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "Invalid witness program length: " + witnessProgram.length);
        }
        if (witnessVersion == 0 && witnessProgram.length != 20 && witnessProgram.length != 32) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "Witness v0 program must be 20 or 32 bytes, got " + witnessProgram.length);
        }

        // Convert 8-bit witness program to 5-bit groups
        byte[] converted = convertBits(witnessProgram, 8, 5, true);

        // Prepend witness version to the 5-bit data
        byte[] data = new byte[1 + converted.length];
        data[0] = (byte) witnessVersion;
        System.arraycopy(converted, 0, data, 1, converted.length);

        return bech32Encode(hrp, data);
    }

    /**
     * Decode a bech32 segwit address.
     *
     * @param hrp      expected human-readable part
     * @param address  bech32 address string (case-insensitive)
     * @return byte array where first byte is witness version, rest is witness program
     * @throws CryptoError if the address is invalid
     */
    public static byte[] decode(String hrp, String address) throws CryptoError {
        if (address.length() == 0) {
            throw new CryptoError(CryptoError.ERR_ENCODING, "Empty address");
        }

        // Bech32 addresses must not exceed 90 characters
        if (address.length() > 90) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "Address too long: " + address.length());
        }

        // Check for mixed case
        boolean hasLower = false;
        boolean hasUpper = false;
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (c >= 'a' && c <= 'z') hasLower = true;
            if (c >= 'A' && c <= 'Z') hasUpper = true;
        }
        if (hasLower && hasUpper) {
            throw new CryptoError(CryptoError.ERR_ENCODING, "Mixed case in bech32 address");
        }

        // Convert to lowercase for processing
        String addr = toLowerCase(address);
        String hrpLower = toLowerCase(hrp);

        // Find the separator '1' (last occurrence)
        int sepPos = addr.lastIndexOf('1');
        if (sepPos < 1) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "Missing separator in bech32 address");
        }
        if (sepPos + 7 > addr.length()) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "Data part too short in bech32 address");
        }

        // Extract HRP and verify
        String addrHrp = addr.substring(0, sepPos);
        if (!addrHrp.equals(hrpLower)) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "HRP mismatch: expected " + hrpLower + ", got " + addrHrp);
        }

        // Decode the data part (after separator)
        String dataPart = addr.substring(sepPos + 1);
        byte[] dataWithChecksum = new byte[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            int idx = CHARSET.indexOf(dataPart.charAt(i));
            if (idx < 0) {
                throw new CryptoError(CryptoError.ERR_ENCODING,
                    "Invalid character in bech32 address: " + dataPart.charAt(i));
            }
            dataWithChecksum[i] = (byte) idx;
        }

        // Verify checksum
        if (!verifyChecksum(hrpLower, dataWithChecksum)) {
            throw new CryptoError(CryptoError.ERR_CHECKSUM,
                "Invalid bech32 checksum");
        }

        // Strip checksum (last 6 values)
        byte[] data = new byte[dataWithChecksum.length - 6];
        System.arraycopy(dataWithChecksum, 0, data, 0, data.length);

        if (data.length < 1) {
            throw new CryptoError(CryptoError.ERR_ENCODING, "Empty data in bech32 address");
        }

        // First value is witness version
        int witnessVersion = data[0] & 0xFF;
        if (witnessVersion > 16) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "Invalid witness version: " + witnessVersion);
        }

        // Convert remaining 5-bit values to 8-bit witness program
        byte[] fiveBitData = new byte[data.length - 1];
        System.arraycopy(data, 1, fiveBitData, 0, fiveBitData.length);
        byte[] witnessProgram = convertBits(fiveBitData, 5, 8, false);

        // Validate witness program length
        if (witnessProgram.length < 2 || witnessProgram.length > 40) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "Invalid witness program length: " + witnessProgram.length);
        }
        if (witnessVersion == 0 && witnessProgram.length != 20 && witnessProgram.length != 32) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "Witness v0 program must be 20 or 32 bytes, got " + witnessProgram.length);
        }

        // Return witness version prepended to program
        byte[] result = new byte[1 + witnessProgram.length];
        result[0] = (byte) witnessVersion;
        System.arraycopy(witnessProgram, 0, result, 1, witnessProgram.length);
        return result;
    }

    // ---- Internal BIP173 methods ----

    /**
     * Compute the Bech32 polymod checksum value.
     */
    private static int polymod(byte[] values) {
        int chk = 1;
        for (int i = 0; i < values.length; i++) {
            int top = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ (values[i] & 0xFF);
            for (int j = 0; j < 5; j++) {
                if (((top >> j) & 1) == 1) {
                    chk ^= GENERATOR[j];
                }
            }
        }
        return chk;
    }

    /**
     * Expand the HRP for checksum computation.
     * Returns: [ch >> 5 for each ch] + [0] + [ch & 31 for each ch]
     */
    private static byte[] hrpExpand(String hrp) {
        int len = hrp.length();
        byte[] ret = new byte[len * 2 + 1];
        for (int i = 0; i < len; i++) {
            ret[i] = (byte) (hrp.charAt(i) >> 5);
        }
        ret[len] = 0;
        for (int i = 0; i < len; i++) {
            ret[len + 1 + i] = (byte) (hrp.charAt(i) & 31);
        }
        return ret;
    }

    /**
     * Verify the bech32 checksum.
     */
    private static boolean verifyChecksum(String hrp, byte[] data) {
        byte[] expanded = hrpExpand(hrp);
        byte[] values = new byte[expanded.length + data.length];
        System.arraycopy(expanded, 0, values, 0, expanded.length);
        System.arraycopy(data, 0, values, expanded.length, data.length);
        return polymod(values) == 1;
    }

    /**
     * Create the bech32 checksum (6 values).
     */
    private static byte[] createChecksum(String hrp, byte[] data) {
        byte[] expanded = hrpExpand(hrp);
        byte[] values = new byte[expanded.length + data.length + 6];
        System.arraycopy(expanded, 0, values, 0, expanded.length);
        System.arraycopy(data, 0, values, expanded.length, data.length);
        // Last 6 bytes are zero (already initialized)
        int mod = polymod(values) ^ 1;
        byte[] ret = new byte[6];
        for (int i = 0; i < 6; i++) {
            ret[i] = (byte) ((mod >> (5 * (5 - i))) & 31);
        }
        return ret;
    }

    /**
     * Encode HRP and 5-bit data values into a bech32 string.
     */
    private static String bech32Encode(String hrp, byte[] data) {
        byte[] checksum = createChecksum(hrp, data);
        StringBuffer sb = new StringBuffer(hrp.length() + 1 + data.length + 6);
        sb.append(hrp);
        sb.append('1');
        for (int i = 0; i < data.length; i++) {
            sb.append(CHARSET.charAt(data[i] & 0xFF));
        }
        for (int i = 0; i < checksum.length; i++) {
            sb.append(CHARSET.charAt(checksum[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Convert between bit groups.
     * General power-of-2 base conversion (e.g. 8-bit to 5-bit or 5-bit to 8-bit).
     *
     * @param data    input data
     * @param fromBits  bits per input value
     * @param toBits    bits per output value
     * @param pad       whether to pad incomplete groups with zeros
     * @return converted data
     * @throws CryptoError if padding is invalid
     */
    private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad)
            throws CryptoError {
        int acc = 0;
        int bits = 0;
        int maxv = (1 << toBits) - 1;

        // Calculate maximum possible output size
        int maxOutLen = (data.length * fromBits + toBits - 1) / toBits;
        byte[] out = new byte[maxOutLen];
        int outIdx = 0;

        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xFF;
            if ((value >> fromBits) != 0) {
                throw new CryptoError(CryptoError.ERR_ENCODING,
                    "Invalid value for " + fromBits + "-bit conversion: " + value);
            }
            acc = (acc << fromBits) | value;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                out[outIdx++] = (byte) ((acc >> bits) & maxv);
            }
        }

        if (pad) {
            if (bits > 0) {
                out[outIdx++] = (byte) ((acc << (toBits - bits)) & maxv);
            }
        } else {
            if (bits >= fromBits) {
                throw new CryptoError(CryptoError.ERR_ENCODING,
                    "Non-zero padding in bit conversion");
            }
            if (((acc << (toBits - bits)) & maxv) != 0) {
                throw new CryptoError(CryptoError.ERR_ENCODING,
                    "Non-zero padding in bit conversion");
            }
        }

        // Trim output to actual size
        byte[] result = new byte[outIdx];
        System.arraycopy(out, 0, result, 0, outIdx);
        return result;
    }

    /**
     * Convert a string to lowercase.
     * Java 1.4 / CLDC compatible — avoids Locale-dependent toLowerCase().
     */
    private static String toLowerCase(String s) {
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') {
                chars[i] = (char) (chars[i] + ('a' - 'A'));
            }
        }
        return new String(chars);
    }
}
