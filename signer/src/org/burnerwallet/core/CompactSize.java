package org.burnerwallet.core;

/**
 * Bitcoin CompactSize (variable-length integer) encoding and decoding.
 *
 * Used in Bitcoin transactions and PSBT binary format to encode lengths
 * and counts. The encoding uses 1, 3, 5, or 9 bytes depending on the
 * value range:
 *
 *   0-252:          1 byte  (value itself)
 *   253-65535:      3 bytes (0xFD + 2-byte little-endian)
 *   65536-2^32-1:   5 bytes (0xFE + 4-byte little-endian)
 *   2^32-2^64-1:    9 bytes (0xFF + 8-byte little-endian)
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class CompactSize {

    /**
     * Read a CompactSize-encoded integer from a byte array at the given offset.
     *
     * @param data   the byte array to read from
     * @param offset the position of the first byte
     * @return a two-element long array: [value, bytesConsumed]
     */
    public static long[] read(byte[] data, int offset) {
        int first = data[offset] & 0xFF;

        if (first < 0xFD) {
            return new long[] { first, 1 };
        } else if (first == 0xFD) {
            long val = (data[offset + 1] & 0xFFL)
                     | ((data[offset + 2] & 0xFFL) << 8);
            return new long[] { val, 3 };
        } else if (first == 0xFE) {
            long val = (data[offset + 1] & 0xFFL)
                     | ((data[offset + 2] & 0xFFL) << 8)
                     | ((data[offset + 3] & 0xFFL) << 16)
                     | ((data[offset + 4] & 0xFFL) << 24);
            return new long[] { val, 5 };
        } else {
            // 0xFF
            long val = (data[offset + 1] & 0xFFL)
                     | ((data[offset + 2] & 0xFFL) << 8)
                     | ((data[offset + 3] & 0xFFL) << 16)
                     | ((data[offset + 4] & 0xFFL) << 24)
                     | ((data[offset + 5] & 0xFFL) << 32)
                     | ((data[offset + 6] & 0xFFL) << 40)
                     | ((data[offset + 7] & 0xFFL) << 48)
                     | ((data[offset + 8] & 0xFFL) << 56);
            return new long[] { val, 9 };
        }
    }

    /**
     * Encode a value as a CompactSize byte array.
     *
     * @param value the non-negative value to encode
     * @return the CompactSize-encoded bytes
     */
    public static byte[] write(long value) {
        if (value < 0xFD) {
            return new byte[] { (byte) value };
        } else if (value <= 0xFFFFL) {
            return new byte[] {
                (byte) 0xFD,
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF)
            };
        } else if (value <= 0xFFFFFFFFL) {
            return new byte[] {
                (byte) 0xFE,
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
            };
        } else {
            return new byte[] {
                (byte) 0xFF,
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 32) & 0xFF),
                (byte) ((value >> 40) & 0xFF),
                (byte) ((value >> 48) & 0xFF),
                (byte) ((value >> 56) & 0xFF)
            };
        }
    }
}
