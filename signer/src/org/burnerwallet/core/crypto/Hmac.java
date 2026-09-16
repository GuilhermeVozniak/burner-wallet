package org.burnerwallet.core.crypto;

/**
 * HMAC (RFC 2104) over any {@link Digest}.
 *
 * The instance is reusable: {@link #doFinal(byte[], int)} leaves it ready
 * for the next message with the same key, which keeps PBKDF2 allocation-free
 * in its inner loop.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class Hmac {

    private final Digest digest;
    private final int blockSize;
    private final int digestSize;
    private final byte[] ipad;
    private final byte[] opad;

    /**
     * @param digest the underlying hash (used exclusively by this HMAC)
     * @param key    the MAC key (any length; longer than the block size is hashed)
     */
    public Hmac(Digest digest, byte[] key) {
        this.digest = digest;
        this.blockSize = digest.getBlockSize();
        this.digestSize = digest.getDigestSize();
        byte[] k = key;
        if (k.length > blockSize) {
            k = new byte[digestSize];
            digest.reset();
            digest.update(key, 0, key.length);
            digest.doFinal(k, 0);
        }
        ipad = new byte[blockSize];
        opad = new byte[blockSize];
        for (int i = 0; i < blockSize; i++) {
            byte kb = i < k.length ? k[i] : 0;
            ipad[i] = (byte) (kb ^ 0x36);
            opad[i] = (byte) (kb ^ 0x5c);
        }
        if (k != key) {
            for (int i = 0; i < k.length; i++) {
                k[i] = 0;
            }
        }
        reset();
    }

    /** @return MAC size in bytes */
    public int getMacSize() {
        return digestSize;
    }

    /** Restart the MAC for a new message with the same key. */
    public void reset() {
        digest.reset();
        digest.update(ipad, 0, blockSize);
    }

    /**
     * Absorb message bytes.
     *
     * @param in  input buffer
     * @param off offset
     * @param len length
     */
    public void update(byte[] in, int off, int len) {
        digest.update(in, off, len);
    }

    /**
     * Absorb a single byte.
     *
     * @param b the byte
     */
    public void update(byte b) {
        byte[] one = new byte[1];
        one[0] = b;
        digest.update(one, 0, 1);
    }

    /**
     * Write the MAC and reset for the next message.
     *
     * @param out    output buffer
     * @param outOff offset for {@link #getMacSize()} bytes
     */
    public void doFinal(byte[] out, int outOff) {
        byte[] inner = new byte[digestSize];
        digest.doFinal(inner, 0);
        digest.update(opad, 0, blockSize);
        digest.update(inner, 0, digestSize);
        digest.doFinal(out, outOff);
        for (int i = 0; i < inner.length; i++) {
            inner[i] = 0;
        }
        reset();
    }

    /** Zero the key material held by this instance. */
    public void destroy() {
        for (int i = 0; i < blockSize; i++) {
            ipad[i] = 0;
            opad[i] = 0;
        }
        digest.reset();
    }
}
