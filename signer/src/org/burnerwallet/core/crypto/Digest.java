package org.burnerwallet.core.crypto;

/**
 * Minimal streaming message-digest interface shared by the in-house
 * SHA-256, SHA-512 and RIPEMD-160 implementations.
 *
 * Java 1.4 compatible (CLDC 1.1): no dependency outside java.lang.
 */
public interface Digest {

    /** @return output size in bytes */
    int getDigestSize();

    /** @return internal block size in bytes (used by HMAC) */
    int getBlockSize();

    /**
     * Absorb input bytes.
     *
     * @param in  input buffer
     * @param off offset of the first byte
     * @param len number of bytes
     */
    void update(byte[] in, int off, int len);

    /**
     * Finish the computation, write the digest and reset for reuse.
     *
     * @param out    output buffer
     * @param outOff offset at which to write {@link #getDigestSize()} bytes
     */
    void doFinal(byte[] out, int outOff);

    /** Discard any buffered input and restore the initial state. */
    void reset();
}
