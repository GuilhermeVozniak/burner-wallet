package org.burnerwallet.chains.bitcoin;

/**
 * Value type representing a parsed raw Bitcoin transaction.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class TxData {
    public int version;
    public TxInput[] inputs;
    public TxOutput[] outputs;
    public int locktime;
}
