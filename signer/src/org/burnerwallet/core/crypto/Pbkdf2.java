package org.burnerwallet.core.crypto;

/**
 * PBKDF2 (RFC 8018) with HMAC-SHA512 as the pseudo-random function.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class Pbkdf2 {

    private Pbkdf2() {
        // static only
    }

    /**
     * Derive {@code dkLen} bytes.
     *
     * @param password   password bytes
     * @param salt       salt bytes
     * @param iterations iteration count (must be at least 1)
     * @param dkLen      output length in bytes
     * @return derived key
     */
    public static byte[] hmacSha512(byte[] password, byte[] salt, int iterations, int dkLen) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be >= 1");
        }
        Hmac prf = new Hmac(new Sha512(), password);
        int hLen = prf.getMacSize();
        int blocks = (dkLen + hLen - 1) / hLen;
        byte[] out = new byte[dkLen];
        byte[] u = new byte[hLen];
        byte[] t = new byte[hLen];
        byte[] counter = new byte[4];
        for (int block = 1; block <= blocks; block++) {
            counter[0] = (byte) (block >>> 24);
            counter[1] = (byte) (block >>> 16);
            counter[2] = (byte) (block >>> 8);
            counter[3] = (byte) block;
            prf.update(salt, 0, salt.length);
            prf.update(counter, 0, 4);
            prf.doFinal(u, 0);
            System.arraycopy(u, 0, t, 0, hLen);
            for (int i = 1; i < iterations; i++) {
                prf.update(u, 0, hLen);
                prf.doFinal(u, 0);
                for (int j = 0; j < hLen; j++) {
                    t[j] ^= u[j];
                }
            }
            int off = (block - 1) * hLen;
            int n = dkLen - off;
            if (n > hLen) {
                n = hLen;
            }
            System.arraycopy(t, 0, out, off, n);
        }
        for (int i = 0; i < hLen; i++) {
            u[i] = 0;
            t[i] = 0;
        }
        prf.destroy();
        return out;
    }
}
