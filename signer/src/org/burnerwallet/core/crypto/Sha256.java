package org.burnerwallet.core.crypto;

/**
 * SHA-256 (FIPS 180-4).
 *
 * Pure Java 1.4 / CLDC 1.1 implementation with no dependencies, so the
 * signer JAR does not need any class the Nokia's runtime lacks. Verified in
 * JUnit against FIPS known answers and against Bouncy Castle on random input.
 */
public final class Sha256 implements Digest {

    /** Digest size in bytes. */
    public static final int DIGEST_SIZE = 32;

    /** Block size in bytes. */
    public static final int BLOCK_SIZE = 64;

    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private final int[] h = new int[8];
    private final int[] w = new int[64];
    private final byte[] buffer = new byte[BLOCK_SIZE];
    private int bufLen;
    private long byteCount;

    /** Create a digest in its initial state. */
    public Sha256() {
        reset();
    }

    /**
     * One-shot convenience.
     *
     * @param in data to hash
     * @return 32-byte digest
     */
    public static byte[] hash(byte[] in) {
        Sha256 d = new Sha256();
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
        h[0] = 0x6a09e667;
        h[1] = 0xbb67ae85;
        h[2] = 0x3c6ef372;
        h[3] = 0xa54ff53a;
        h[4] = 0x510e527f;
        h[5] = 0x9b05688c;
        h[6] = 0x1f83d9ab;
        h[7] = 0x5be0cd19;
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
        for (int i = 0; i < 8; i++) {
            buffer[56 + i] = (byte) (bits >>> (56 - 8 * i));
        }
        processBlock(buffer, 0);
        for (int i = 0; i < 8; i++) {
            out[outOff + 4 * i] = (byte) (h[i] >>> 24);
            out[outOff + 4 * i + 1] = (byte) (h[i] >>> 16);
            out[outOff + 4 * i + 2] = (byte) (h[i] >>> 8);
            out[outOff + 4 * i + 3] = (byte) h[i];
        }
        reset();
    }

    private void processBlock(byte[] b, int off) {
        for (int i = 0; i < 16; i++) {
            int j = off + 4 * i;
            w[i] = ((b[j] & 0xff) << 24) | ((b[j + 1] & 0xff) << 16)
                 | ((b[j + 2] & 0xff) << 8) | (b[j + 3] & 0xff);
        }
        for (int i = 16; i < 64; i++) {
            int x = w[i - 15];
            int s0 = rotr(x, 7) ^ rotr(x, 18) ^ (x >>> 3);
            int y = w[i - 2];
            int s1 = rotr(y, 17) ^ rotr(y, 19) ^ (y >>> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        int a = h[0];
        int bb = h[1];
        int c = h[2];
        int d = h[3];
        int e = h[4];
        int f = h[5];
        int g = h[6];
        int hh = h[7];
        for (int i = 0; i < 64; i++) {
            int s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            int ch = (e & f) ^ (~e & g);
            int t1 = hh + s1 + ch + K[i] + w[i];
            int s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            int maj = (a & bb) ^ (a & c) ^ (bb & c);
            int t2 = s0 + maj;
            hh = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = bb;
            bb = a;
            a = t1 + t2;
        }
        h[0] += a;
        h[1] += bb;
        h[2] += c;
        h[3] += d;
        h[4] += e;
        h[5] += f;
        h[6] += g;
        h[7] += hh;
    }

    private static int rotr(int x, int n) {
        return (x >>> n) | (x << (32 - n));
    }
}
