package org.burnerwallet.chains.bitcoin;

/**
 * Value type representing a single PSBT output map (BIP174).
 *
 * Holds BIP32 derivation info for change output identification.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class PsbtOutput {

    /** Raw BIP32 derivation data (fingerprint + path); null if not present. */
    public byte[] bip32Derivation;

    /** Public key associated with BIP32 derivation; null if not present. */
    public byte[] bip32PubKey;
}
