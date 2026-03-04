package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CompactSize;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;

/**
 * BIP143 segwit signature hash computation for P2WPKH inputs.
 *
 * Implements the transaction digest algorithm defined in BIP143
 * (https://github.com/bitcoin/bips/blob/master/bip-0143.mediawiki)
 * which is used for signing native segwit (P2WPKH) inputs.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class Bip143Sighash {

    /** Sign all outputs. */
    public static final int SIGHASH_ALL = 0x01;

    /** Sign no outputs (allows any output modification). */
    public static final int SIGHASH_NONE = 0x02;

    /** Sign only the output at the same index as the input. */
    public static final int SIGHASH_SINGLE = 0x03;

    /** Sign only this input (can be combined with ALL/NONE/SINGLE). */
    public static final int SIGHASH_ANYONECANPAY = 0x80;

    private static final byte[] ZEROS_32 = new byte[32];

    /**
     * Build the P2WPKH scriptCode for a given public key hash.
     *
     * The scriptCode for P2WPKH is the same as a P2PKH script:
     *   OP_DUP OP_HASH160 OP_PUSH20 &lt;pubKeyHash20&gt; OP_EQUALVERIFY OP_CHECKSIG
     *
     * @param pubKeyHash20 the 20-byte hash of the public key
     * @return 25-byte scriptCode
     */
    public static byte[] p2wpkhScriptCode(byte[] pubKeyHash20) {
        byte[] script = new byte[25];
        script[0] = 0x76;   // OP_DUP
        script[1] = (byte) 0xa9; // OP_HASH160
        script[2] = 0x14;   // OP_PUSH20 (20 bytes)
        System.arraycopy(pubKeyHash20, 0, script, 3, 20);
        script[23] = (byte) 0x88; // OP_EQUALVERIFY
        script[24] = (byte) 0xac; // OP_CHECKSIG
        return script;
    }

    /**
     * Compute the BIP143 sighash digest for a segwit input.
     *
     * The preimage is constructed as:
     *   nVersion(4LE) + hashPrevouts(32) + hashSequence(32)
     *   + outpoint(36) + scriptCode(varint+bytes) + value(8LE)
     *   + nSequence(4LE) + hashOutputs(32) + nLocktime(4LE)
     *   + sighashType(4LE)
     *
     * @param tx          the parsed transaction
     * @param inputIndex  the index of the input being signed
     * @param scriptCode  the scriptCode for the input (use p2wpkhScriptCode for P2WPKH)
     * @param value       the value of the UTXO being spent, in satoshis
     * @param sighashType the sighash type (e.g. SIGHASH_ALL)
     * @return the 32-byte sighash digest
     * @throws CryptoError if the input index is out of range
     */
    public static byte[] computeSighash(TxData tx, int inputIndex,
            byte[] scriptCode, long value, int sighashType) throws CryptoError {

        if (inputIndex < 0 || inputIndex >= tx.inputs.length) {
            throw new CryptoError(CryptoError.ERR_SIGNING,
                "Input index out of range: " + inputIndex);
        }

        int baseType = sighashType & 0x1F;
        boolean anyoneCanPay = (sighashType & SIGHASH_ANYONECANPAY) != 0;

        // hashPrevouts
        byte[] hashPrevouts;
        if (anyoneCanPay) {
            hashPrevouts = ZEROS_32;
        } else {
            hashPrevouts = computeHashPrevouts(tx);
        }

        // hashSequence
        byte[] hashSequence;
        if (anyoneCanPay || baseType == SIGHASH_SINGLE || baseType == SIGHASH_NONE) {
            hashSequence = ZEROS_32;
        } else {
            hashSequence = computeHashSequence(tx);
        }

        // hashOutputs
        byte[] hashOutputs;
        if (baseType == SIGHASH_ALL) {
            hashOutputs = computeHashOutputsAll(tx);
        } else if (baseType == SIGHASH_SINGLE && inputIndex < tx.outputs.length) {
            hashOutputs = computeHashOutputSingle(tx, inputIndex);
        } else {
            hashOutputs = ZEROS_32;
        }

        // Build the preimage
        TxInput signingInput = tx.inputs[inputIndex];
        byte[] scriptCodeLen = CompactSize.write(scriptCode.length);

        // Total preimage size:
        // 4 (version) + 32 (hashPrevouts) + 32 (hashSequence)
        // + 32 (prevTxHash) + 4 (prevIndex) = outpoint 36
        // + scriptCodeLen.length + scriptCode.length
        // + 8 (value) + 4 (sequence)
        // + 32 (hashOutputs) + 4 (locktime) + 4 (sighashType)
        int preimageLen = 4 + 32 + 32
            + 32 + 4
            + scriptCodeLen.length + scriptCode.length
            + 8 + 4
            + 32 + 4 + 4;

        byte[] preimage = new byte[preimageLen];
        int offset = 0;

        // nVersion (4 bytes LE)
        TxSerializer.writeInt32LE(preimage, offset, tx.version);
        offset += 4;

        // hashPrevouts (32 bytes)
        System.arraycopy(hashPrevouts, 0, preimage, offset, 32);
        offset += 32;

        // hashSequence (32 bytes)
        System.arraycopy(hashSequence, 0, preimage, offset, 32);
        offset += 32;

        // outpoint: prevTxHash (32 bytes) + prevIndex (4 bytes LE)
        System.arraycopy(signingInput.prevTxHash, 0, preimage, offset, 32);
        offset += 32;
        TxSerializer.writeInt32LE(preimage, offset, signingInput.prevIndex);
        offset += 4;

        // scriptCode (varint length + bytes)
        System.arraycopy(scriptCodeLen, 0, preimage, offset, scriptCodeLen.length);
        offset += scriptCodeLen.length;
        System.arraycopy(scriptCode, 0, preimage, offset, scriptCode.length);
        offset += scriptCode.length;

        // value (8 bytes LE)
        TxSerializer.writeInt64LE(preimage, offset, value);
        offset += 8;

        // nSequence of the input being signed (4 bytes LE)
        TxSerializer.writeUInt32LE(preimage, offset, signingInput.sequence);
        offset += 4;

        // hashOutputs (32 bytes)
        System.arraycopy(hashOutputs, 0, preimage, offset, 32);
        offset += 32;

        // nLocktime (4 bytes LE)
        TxSerializer.writeInt32LE(preimage, offset, tx.locktime);
        offset += 4;

        // sighashType (4 bytes LE)
        TxSerializer.writeInt32LE(preimage, offset, sighashType);

        return HashUtils.doubleSha256(preimage);
    }

    /**
     * Compute hashPrevouts: double-SHA256 of all input outpoints concatenated.
     * Each outpoint is prevTxHash(32) + prevIndex(4LE) = 36 bytes.
     */
    private static byte[] computeHashPrevouts(TxData tx) {
        byte[] data = new byte[tx.inputs.length * 36];
        int offset = 0;
        for (int i = 0; i < tx.inputs.length; i++) {
            System.arraycopy(tx.inputs[i].prevTxHash, 0, data, offset, 32);
            offset += 32;
            TxSerializer.writeInt32LE(data, offset, tx.inputs[i].prevIndex);
            offset += 4;
        }
        return HashUtils.doubleSha256(data);
    }

    /**
     * Compute hashSequence: double-SHA256 of all input sequences concatenated.
     * Each sequence is 4 bytes LE.
     */
    private static byte[] computeHashSequence(TxData tx) {
        byte[] data = new byte[tx.inputs.length * 4];
        int offset = 0;
        for (int i = 0; i < tx.inputs.length; i++) {
            TxSerializer.writeUInt32LE(data, offset, tx.inputs[i].sequence);
            offset += 4;
        }
        return HashUtils.doubleSha256(data);
    }

    /**
     * Compute hashOutputs for SIGHASH_ALL: double-SHA256 of all outputs serialized.
     * Each output is value(8LE) + compactSize(scriptPubKey.length) + scriptPubKey.
     */
    private static byte[] computeHashOutputsAll(TxData tx) {
        // Calculate total size
        int size = 0;
        byte[][] scriptLenBytes = new byte[tx.outputs.length][];
        for (int i = 0; i < tx.outputs.length; i++) {
            scriptLenBytes[i] = CompactSize.write(tx.outputs[i].scriptPubKey.length);
            size += 8 + scriptLenBytes[i].length + tx.outputs[i].scriptPubKey.length;
        }

        byte[] data = new byte[size];
        int offset = 0;
        for (int i = 0; i < tx.outputs.length; i++) {
            TxSerializer.writeInt64LE(data, offset, tx.outputs[i].value);
            offset += 8;
            System.arraycopy(scriptLenBytes[i], 0, data, offset, scriptLenBytes[i].length);
            offset += scriptLenBytes[i].length;
            System.arraycopy(tx.outputs[i].scriptPubKey, 0, data, offset,
                tx.outputs[i].scriptPubKey.length);
            offset += tx.outputs[i].scriptPubKey.length;
        }

        return HashUtils.doubleSha256(data);
    }

    /**
     * Compute hashOutputs for SIGHASH_SINGLE: double-SHA256 of the single
     * output at the matching input index.
     */
    private static byte[] computeHashOutputSingle(TxData tx, int index) {
        TxOutput output = tx.outputs[index];
        byte[] scriptLenBytes = CompactSize.write(output.scriptPubKey.length);
        int size = 8 + scriptLenBytes.length + output.scriptPubKey.length;

        byte[] data = new byte[size];
        int offset = 0;
        TxSerializer.writeInt64LE(data, offset, output.value);
        offset += 8;
        System.arraycopy(scriptLenBytes, 0, data, offset, scriptLenBytes.length);
        offset += scriptLenBytes.length;
        System.arraycopy(output.scriptPubKey, 0, data, offset, output.scriptPubKey.length);

        return HashUtils.doubleSha256(data);
    }
}
