package org.burnerwallet.core.crypto;

/**
 * RIPEMD-160 (Dobbertin, Bosselaers, Preneel 1996).
 *
 * Pure Java 1.4 / CLDC 1.1 implementation. Verified in JUnit against the
 * published known answers, the BIP84 address vectors and Bouncy Castle.
 */
public final class Ripemd160 implements Digest {

    /** Digest size in bytes. */
    public static final int DIGEST_SIZE = 20;

    /** Block size in bytes. */
    public static final int BLOCK_SIZE = 64;

    /** Message word order, left line. */
    private static final int[] RL = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
        4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13
    };

    /** Message word order, right line. */
    private static final int[] RR = {
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
        12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11
    };

    /** Rotation amounts, left line. */
    private static final int[] SL = {
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
        9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6
    };

    /** Rotation amounts, right line. */
    private static final int[] SR = {
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
        8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11
    };

    private static final int[] KL = {0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC, 0xA953FD4E};
    private static final int[] KR = {0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x7A6D76E9, 0x00000000};

    private final int[] h = new int[5];
    private final int[] x = new int[16];
    private final byte[] buffer = new byte[BLOCK_SIZE];
    private int bufLen;
    private long byteCount;

    /** Create a digest in its initial state. */
    public Ripemd160() {
        reset();
    }

    /**
     * One-shot convenience.
     *
     * @param in data to hash
     * @return 20-byte digest
     */
    public static byte[] hash(byte[] in) {
        Ripemd160 d = new Ripemd160();
        d.update(in, 0, in.length);
        byte[] out = new byte[DIGEST_SIZE];
        d.doFinal(out, 0);
        return out;
    }

    public int getDigestSize() {
        return DIGEST_SIZE;
    }

    public int getBlockSize() {
        return BLOCK_SIZE;
    }

    public void reset() {
        h[0] = 0x67452301;
        h[1] = 0xEFCDAB89;
        h[2] = 0x98BADCFE;
        h[3] = 0x10325476;
        h[4] = 0xC3D2E1F0;
        bufLen = 0;
        byteCount = 0;
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = 0;
        }
    }

    public void update(byte[] in, int off, int len) {
        byteCount += len;
        while (len > 0) {
            int n = BLOCK_SIZE - bufLen;
            if (n > len) {
                n = len;
            }
            System.arraycopy(in, off, buffer, bufLen, n);
            bufLen += n;
            off += n;
            len -= n;
            if (bufLen == BLOCK_SIZE) {
                processBlock(buffer, 0);
                bufLen = 0;
            }
        }
    }

    public void doFinal(byte[] out, int outOff) {
        long bits = byteCount << 3;
        buffer[bufLen++] = (byte) 0x80;
        if (bufLen > 56) {
            while (bufLen < BLOCK_SIZE) {
                buffer[bufLen++] = 0;
            }
            processBlock(buffer, 0);
            bufLen = 0;
        }
        while (bufLen < 56) {
            buffer[bufLen++] = 0;
        }
        // Length is little-endian in RIPEMD-160
        for (int i = 0; i < 8; i++) {
            buffer[56 + i] = (byte) (bits >>> (8 * i));
        }
        processBlock(buffer, 0);
        for (int i = 0; i < 5; i++) {
            out[outOff + 4 * i] = (byte) h[i];
            out[outOff + 4 * i + 1] = (byte) (h[i] >>> 8);
            out[outOff + 4 * i + 2] = (byte) (h[i] >>> 16);
            out[outOff + 4 * i + 3] = (byte) (h[i] >>> 24);
        }
        reset();
    }

    private void processBlock(byte[] b, int off) {
        for (int i = 0; i < 16; i++) {
            int j = off + 4 * i;
            x[i] = (b[j] & 0xff) | ((b[j + 1] & 0xff) << 8)
                 | ((b[j + 2] & 0xff) << 16) | ((b[j + 3] & 0xff) << 24);
        }
        int al = h[0];
        int bl = h[1];
        int cl = h[2];
        int dl = h[3];
        int el = h[4];
        int ar = al;
        int br = bl;
        int cr = cl;
        int dr = dl;
        int er = el;
        for (int j = 0; j < 80; j++) {
            int round = j >>> 4;
            int t = rotl(al + f(round, bl, cl, dl) + x[RL[j]] + KL[round], SL[j]) + el;
            al = el;
            el = dl;
            dl = rotl(cl, 10);
            cl = bl;
            bl = t;
            t = rotl(ar + f(4 - round, br, cr, dr) + x[RR[j]] + KR[round], SR[j]) + er;
            ar = er;
            er = dr;
            dr = rotl(cr, 10);
            cr = br;
            br = t;
        }
        int t = h[1] + cl + dr;
        h[1] = h[2] + dl + er;
        h[2] = h[3] + el + ar;
        h[3] = h[4] + al + br;
        h[4] = h[0] + bl + cr;
        h[0] = t;
    }

    private static int f(int round, int x, int y, int z) {
        switch (round) {
            case 0:
                return x ^ y ^ z;
            case 1:
                return (x & y) | (~x & z);
            case 2:
                return (x | ~y) ^ z;
            case 3:
                return (x & z) | (y & ~z);
            default:
                return x ^ (y | ~z);
        }
    }

    private static int rotl(int x, int n) {
        return (x << n) | (x >>> (32 - n));
    }
}
