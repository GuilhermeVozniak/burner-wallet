package org.burnerwallet.core.crypto;

/**
 * AES block cipher (FIPS 197) for 128-, 192- and 256-bit keys.
 *
 * The S-box and its inverse are generated at class-load time from the
 * GF(2^8) arithmetic definition instead of being typed in as tables. Table
 * lookups are not constant-time; this matches the footprint-oriented Bouncy
 * Castle engine it replaces and is documented in the threat model.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class Aes {

    /** Block size in bytes. */
    public static final int BLOCK_SIZE = 16;

    private static final int[] SBOX = new int[256];
    private static final int[] INV_SBOX = new int[256];
    private static final int[] EXP = new int[256];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x ^= xtime(x);
        }
        EXP[255] = EXP[0];
        for (int a = 0; a < 256; a++) {
            int inv = a == 0 ? 0 : EXP[255 - LOG[a]];
            int s = inv ^ rotl8(inv, 1) ^ rotl8(inv, 2) ^ rotl8(inv, 3) ^ rotl8(inv, 4) ^ 0x63;
            SBOX[a] = s & 0xff;
            INV_SBOX[s & 0xff] = a;
        }
    }

    private final int[] roundKeys;
    private final int rounds;

    /**
     * @param key 16, 24 or 32 key bytes
     */
    public Aes(byte[] key) {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
            throw new IllegalArgumentException("AES key must be 16, 24 or 32 bytes");
        }
        int nk = key.length / 4;
        rounds = nk + 6;
        roundKeys = new int[4 * (rounds + 1)];
        for (int i = 0; i < nk; i++) {
            roundKeys[i] = ((key[4 * i] & 0xff) << 24) | ((key[4 * i + 1] & 0xff) << 16)
                         | ((key[4 * i + 2] & 0xff) << 8) | (key[4 * i + 3] & 0xff);
        }
        int rcon = 1;
        for (int i = nk; i < roundKeys.length; i++) {
            int temp = roundKeys[i - 1];
            if (i % nk == 0) {
                temp = subWord((temp << 8) | (temp >>> 24)) ^ (rcon << 24);
                rcon = xtime(rcon);
            } else if (nk > 6 && i % nk == 4) {
                temp = subWord(temp);
            }
            roundKeys[i] = roundKeys[i - nk] ^ temp;
        }
    }

    /** Zero the expanded key. */
    public void destroy() {
        for (int i = 0; i < roundKeys.length; i++) {
            roundKeys[i] = 0;
        }
    }

    /**
     * Encrypt one 16-byte block.
     *
     * @param in     input buffer
     * @param inOff  input offset
     * @param out    output buffer
     * @param outOff output offset
     */
    public void encryptBlock(byte[] in, int inOff, byte[] out, int outOff) {
        int[] s = new int[16];
        int[] t = new int[16];
        for (int i = 0; i < 16; i++) {
            s[i] = in[inOff + i] & 0xff;
        }
        addRoundKey(s, 0);
        for (int round = 1; round < rounds; round++) {
            for (int i = 0; i < 16; i++) {
                s[i] = SBOX[s[i]];
            }
            shiftRows(s, t);
            mixColumns(t, s);
            addRoundKey(s, round);
        }
        for (int i = 0; i < 16; i++) {
            s[i] = SBOX[s[i]];
        }
        shiftRows(s, t);
        addRoundKey(t, rounds);
        for (int i = 0; i < 16; i++) {
            out[outOff + i] = (byte) t[i];
        }
    }

    /**
     * Decrypt one 16-byte block.
     *
     * @param in     input buffer
     * @param inOff  input offset
     * @param out    output buffer
     * @param outOff output offset
     */
    public void decryptBlock(byte[] in, int inOff, byte[] out, int outOff) {
        int[] s = new int[16];
        int[] t = new int[16];
        for (int i = 0; i < 16; i++) {
            s[i] = in[inOff + i] & 0xff;
        }
        addRoundKey(s, rounds);
        for (int round = rounds - 1; round >= 1; round--) {
            invShiftRows(s, t);
            for (int i = 0; i < 16; i++) {
                t[i] = INV_SBOX[t[i]];
            }
            addRoundKey(t, round);
            invMixColumns(t, s);
        }
        invShiftRows(s, t);
        for (int i = 0; i < 16; i++) {
            t[i] = INV_SBOX[t[i]];
        }
        addRoundKey(t, 0);
        for (int i = 0; i < 16; i++) {
            out[outOff + i] = (byte) t[i];
        }
    }

    // State layout: s[row + 4 * column], matching FIPS 197 input ordering.

    private void addRoundKey(int[] s, int round) {
        for (int c = 0; c < 4; c++) {
            int w = roundKeys[4 * round + c];
            s[4 * c] ^= (w >>> 24) & 0xff;
            s[4 * c + 1] ^= (w >>> 16) & 0xff;
            s[4 * c + 2] ^= (w >>> 8) & 0xff;
            s[4 * c + 3] ^= w & 0xff;
        }
    }

    private static void shiftRows(int[] s, int[] t) {
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                t[r + 4 * c] = s[r + 4 * ((c + r) & 3)];
            }
        }
    }

    private static void invShiftRows(int[] s, int[] t) {
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                t[r + 4 * c] = s[r + 4 * ((c - r) & 3)];
            }
        }
    }

    private static void mixColumns(int[] s, int[] t) {
        for (int c = 0; c < 4; c++) {
            int a0 = s[4 * c];
            int a1 = s[4 * c + 1];
            int a2 = s[4 * c + 2];
            int a3 = s[4 * c + 3];
            t[4 * c] = xtime(a0) ^ mul3(a1) ^ a2 ^ a3;
            t[4 * c + 1] = a0 ^ xtime(a1) ^ mul3(a2) ^ a3;
            t[4 * c + 2] = a0 ^ a1 ^ xtime(a2) ^ mul3(a3);
            t[4 * c + 3] = mul3(a0) ^ a1 ^ a2 ^ xtime(a3);
        }
    }

    private static void invMixColumns(int[] s, int[] t) {
        for (int c = 0; c < 4; c++) {
            int a0 = s[4 * c];
            int a1 = s[4 * c + 1];
            int a2 = s[4 * c + 2];
            int a3 = s[4 * c + 3];
            t[4 * c] = gmul(a0, 14) ^ gmul(a1, 11) ^ gmul(a2, 13) ^ gmul(a3, 9);
            t[4 * c + 1] = gmul(a0, 9) ^ gmul(a1, 14) ^ gmul(a2, 11) ^ gmul(a3, 13);
            t[4 * c + 2] = gmul(a0, 13) ^ gmul(a1, 9) ^ gmul(a2, 14) ^ gmul(a3, 11);
            t[4 * c + 3] = gmul(a0, 11) ^ gmul(a1, 13) ^ gmul(a2, 9) ^ gmul(a3, 14);
        }
    }

    private static int subWord(int w) {
        return (SBOX[(w >>> 24) & 0xff] << 24) | (SBOX[(w >>> 16) & 0xff] << 16)
             | (SBOX[(w >>> 8) & 0xff] << 8) | SBOX[w & 0xff];
    }

    private static int xtime(int a) {
        int r = a << 1;
        if ((a & 0x80) != 0) {
            r ^= 0x1b;
        }
        return r & 0xff;
    }

    private static int mul3(int a) {
        return xtime(a) ^ a;
    }

    private static int gmul(int a, int b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return EXP[(LOG[a] + LOG[b]) % 255];
    }

    private static int rotl8(int a, int n) {
        return ((a << n) | (a >>> (8 - n))) & 0xff;
    }
}
