package org.burnerwallet.chains.bitcoin;

/**
 * Value type representing a parsed BIP174 PSBT (Partially Signed Bitcoin Transaction).
 *
 * Contains the unsigned transaction (both raw bytes and parsed TxData),
 * plus per-input and per-output metadata maps.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class PsbtTransaction {

    /** Raw bytes of the unsigned transaction from the global map. */
    public byte[] unsignedTxBytes;

    /** Parsed unsigned transaction. */
    public TxData unsignedTx;

    /** Per-input PSBT metadata maps. Length matches unsignedTx.inputs.length. */
    public PsbtInput[] inputs;

    /** Per-output PSBT metadata maps. Length matches unsignedTx.outputs.length. */
    public PsbtOutput[] outputs;

    /**
     * Sum of all witness UTXO values across inputs.
     * Inputs without a witness UTXO (value == -1) are skipped.
     *
     * @return total input value in satoshis
     */
    public long getTotalInputValue() {
        long total = 0;
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i].witnessUtxoValue != -1) {
                total += inputs[i].witnessUtxoValue;
            }
        }
        return total;
    }

    /**
     * Sum of all output values from the unsigned transaction.
     *
     * @return total output value in satoshis
     */
    public long getTotalOutputValue() {
        long total = 0;
        for (int i = 0; i < unsignedTx.outputs.length; i++) {
            total += unsignedTx.outputs[i].value;
        }
        return total;
    }

    /**
     * Calculate the transaction fee.
     *
     * @return fee in satoshis (getTotalInputValue - getTotalOutputValue)
     */
    public long getFee() {
        return getTotalInputValue() - getTotalOutputValue();
    }
}
