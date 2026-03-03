package org.burnerwallet.core;

/**
 * Byte array utility methods.
 * Replaces java.util.Arrays methods not available in CLDC 1.1.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class ByteArrayUtils {

    /**
     * Concatenate two byte arrays into a new array.
     */
    public static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /**
     * Copy the first {@code newLength} bytes of {@code src} into a new array.
     * If {@code newLength} exceeds {@code src.length}, the extra bytes are zero.
     */
    public static byte[] copyOf(byte[] src, int newLength) {
        byte[] result = new byte[newLength];
        int len = src.length < newLength ? src.length : newLength;
        System.arraycopy(src, 0, result, 0, len);
        return result;
    }

    /**
     * Copy a range of bytes from {@code src} into a new array.
     *
     * @param from inclusive start index
     * @param to   exclusive end index
     */
    public static byte[] copyOfRange(byte[] src, int from, int to) {
        int len = to - from;
        byte[] result = new byte[len];
        System.arraycopy(src, from, result, 0, len);
        return result;
    }

    /**
     * Constant-time comparison of two byte arrays.
     * Returns true if and only if both arrays have the same length and
     * identical contents. Runs in time proportional to the shorter array
     * to avoid timing side-channels.
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= (a[i] ^ b[i]);
        }
        return diff == 0;
    }

    /**
     * Fill all bytes in the array with zero.
     * Useful for wiping sensitive key material.
     */
    public static void zeroFill(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] = 0;
        }
    }
}
