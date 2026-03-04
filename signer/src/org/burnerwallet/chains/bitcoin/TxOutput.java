package org.burnerwallet.chains.bitcoin;

/**
 * Value type representing a single transaction output.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class TxOutput {
    /** Output value in satoshis (int64). */
    public long value;

    /** Serialized output script (scriptPubKey). */
    public byte[] scriptPubKey;
}
