package org.burnerwallet.chains.bitcoin;

/**
 * Value type representing a single PSBT input map (BIP174).
 *
 * Holds witness UTXO data, sighash type, BIP32 derivation info,
 * and partial signatures extracted during PSBT parsing.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class PsbtInput {

    /** Witness UTXO value in satoshis; -1 if not present. */
    public long witnessUtxoValue = -1;

    /** Witness UTXO scriptPubKey; null if not present. */
    public byte[] witnessUtxoScript;

    /** Full previous transaction bytes (non-witness UTXO); null if not present. */
    public byte[] nonWitnessUtxo;

    /** Sighash type; defaults to SIGHASH_ALL (0x01). */
    public int sighashType = 0x01;

    /** Raw BIP32 derivation data (fingerprint + path); null if not present. */
    public byte[] bip32Derivation;

    /** Public key associated with BIP32 derivation; null if not present. */
    public byte[] bip32PubKey;

    /** Public key for partial signature; null if not present. */
    public byte[] partialSigKey;

    /** DER-encoded partial signature value; null if not present. */
    public byte[] partialSigValue;
}
