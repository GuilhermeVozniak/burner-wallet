package org.burnerwallet.storage;

/**
 * Result of a successful wallet unlock: the decrypted seed and the stored
 * BIP39 passphrase, obtained from a single PBKDF2 derivation.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class UnlockResult {

    /** 64-byte BIP39 seed. Caller owns it and must zero it when done. */
    public final byte[] seed;

    /** BIP39 passphrase (empty string if none). */
    public final String passphrase;

    UnlockResult(byte[] seed, String passphrase) {
        this.seed = seed;
        this.passphrase = passphrase;
    }
}
