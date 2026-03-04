package org.burnerwallet.chains.bitcoin;

/**
 * Value type representing a single transaction input.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class TxInput {
    /** Previous transaction hash, 32 bytes in internal byte order. */
    public byte[] prevTxHash;

    /** Index of the output in the previous transaction (uint32). */
    public int prevIndex;

    /** Script signature (empty for unsigned transactions). */
    public byte[] scriptSig;

    /** Sequence number (uint32, stored as long to avoid sign issues). */
    public long sequence;
}
