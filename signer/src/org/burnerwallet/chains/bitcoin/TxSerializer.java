package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CompactSize;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;

/**
 * Parser and serializer for raw Bitcoin transactions.
 *
 * {@link #parse} handles the standard (non-witness) serialization format:
 *   version | input_count | inputs... | output_count | outputs... | locktime
 * which is what a PSBT's unsigned transaction must use.
 *
 * {@link #parseAllowWitness} additionally accepts the BIP144 witness
 * serialization (marker 0x00, flag 0x01, witness stacks after the outputs),
 * which is how companions serialize {@code non_witness_utxo} previous
 * transactions. Witness data is skipped, so {@link #computeTxid} on the
 * result yields the txid (which never covers witness data).
 *
 * All counts and lengths are bounds-checked against the remaining input so
 * a malformed transaction fails with {@link CryptoError} instead of an
 * unbounded allocation or an {@code ArrayIndexOutOfBoundsException}.
 *
 * The LE (little-endian) helper methods are package-visible so that
 * Bip143Sighash can reuse them without duplication.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class TxSerializer {

    private static final int ERR_TX_PARSE = 10;

    /** Smallest possible input: prevhash(32) + index(4) + scriptlen(1) + sequence(4). */
    private static final int MIN_INPUT_SIZE = 41;

    /** Smallest possible output: value(8) + scriptlen(1). */
    private static final int MIN_OUTPUT_SIZE = 9;

    /**
     * Parse raw (non-witness) transaction bytes into a TxData structure.
     *
     * @param data the raw serialized transaction
     * @return parsed TxData
     * @throws CryptoError if the data is malformed or truncated
     */
    public static TxData parse(byte[] data) throws CryptoError {
        return parseInternal(data, false);
    }

    /**
     * Parse transaction bytes that may use the BIP144 witness serialization.
     * Witness stacks are validated for structure and skipped.
     *
     * @param data the raw serialized transaction (with or without witnesses)
     * @return parsed TxData (inputs/outputs only; no witness data retained)
     * @throws CryptoError if the data is malformed or truncated
     */
    public static TxData parseAllowWitness(byte[] data) throws CryptoError {
        return parseInternal(data, true);
    }

    /**
     * Compute the transaction id: double SHA-256 of the non-witness
     * serialization, in internal byte order (the same order used for
     * {@link TxInput#prevTxHash}).
     *
     * @param tx the parsed transaction
     * @return 32-byte txid in internal byte order
     */
    public static byte[] computeTxid(TxData tx) {
        return HashUtils.doubleSha256(serialize(tx));
    }

    private static TxData parseInternal(byte[] data, boolean allowWitness)
            throws CryptoError {
        if (data == null || data.length < 10) {
            throw new CryptoError(ERR_TX_PARSE, "Transaction too short");
        }

        int offset = 0;
        TxData tx = new TxData();

        // Version (4 bytes LE, signed int32)
        tx.version = readInt32LE(data, offset);
        offset += 4;

        // BIP144 marker (0x00) + flag (0x01)
        boolean segwit = false;
        if (allowWitness && data.length - offset >= 2
                && data[offset] == 0x00 && data[offset + 1] == 0x01) {
            segwit = true;
            offset += 2;
        }

        // Input count (CompactSize)
        long[] csResult = CompactSize.readChecked(data, offset);
        offset += (int) csResult[1];
        if (csResult[0] < 0 || csResult[0] > (data.length - offset) / MIN_INPUT_SIZE) {
            throw new CryptoError(ERR_TX_PARSE, "Bad input count");
        }
        int inputCount = (int) csResult[0];

        // Parse inputs
        tx.inputs = new TxInput[inputCount];
        for (int i = 0; i < inputCount; i++) {
            TxInput input = new TxInput();

            // Previous transaction hash (32 bytes, kept in internal byte order)
            // + previous output index (4 bytes LE)
            require(data, offset, 36);
            input.prevTxHash = ByteArrayUtils.copyOfRange(data, offset, offset + 32);
            offset += 32;
            input.prevIndex = readInt32LE(data, offset);
            offset += 4;

            // ScriptSig length + data
            csResult = CompactSize.readChecked(data, offset);
            offset += (int) csResult[1];
            int scriptLen = lengthField(csResult[0], data, offset);
            input.scriptSig = ByteArrayUtils.copyOfRange(data, offset, offset + scriptLen);
            offset += scriptLen;

            // Sequence (4 bytes LE, uint32 stored as long)
            require(data, offset, 4);
            input.sequence = readUInt32LE(data, offset);
            offset += 4;

            tx.inputs[i] = input;
        }

        // Output count (CompactSize)
        csResult = CompactSize.readChecked(data, offset);
        offset += (int) csResult[1];
        if (csResult[0] < 0 || csResult[0] > (data.length - offset) / MIN_OUTPUT_SIZE) {
            throw new CryptoError(ERR_TX_PARSE, "Bad output count");
        }
        int outputCount = (int) csResult[0];

        // Parse outputs
        tx.outputs = new TxOutput[outputCount];
        for (int i = 0; i < outputCount; i++) {
            TxOutput output = new TxOutput();

            // Value (8 bytes LE, int64)
            require(data, offset, 8);
            output.value = readInt64LE(data, offset);
            offset += 8;

            // ScriptPubKey length + data
            csResult = CompactSize.readChecked(data, offset);
            offset += (int) csResult[1];
            int scriptLen = lengthField(csResult[0], data, offset);
            output.scriptPubKey = ByteArrayUtils.copyOfRange(data, offset, offset + scriptLen);
            offset += scriptLen;

            tx.outputs[i] = output;
        }

        // Witness stacks: one per input, each a vector of byte strings
        if (segwit) {
            for (int i = 0; i < inputCount; i++) {
                csResult = CompactSize.readChecked(data, offset);
                offset += (int) csResult[1];
                if (csResult[0] < 0 || csResult[0] > data.length - offset) {
                    throw new CryptoError(ERR_TX_PARSE, "Bad witness item count");
                }
                int items = (int) csResult[0];
                for (int j = 0; j < items; j++) {
                    csResult = CompactSize.readChecked(data, offset);
                    offset += (int) csResult[1];
                    int itemLen = lengthField(csResult[0], data, offset);
                    offset += itemLen;
                }
            }
        }

        // Locktime (4 bytes LE, int32)
        require(data, offset, 4);
        tx.locktime = readInt32LE(data, offset);
        offset += 4;

        if (offset != data.length) {
            throw new CryptoError(ERR_TX_PARSE, "Trailing bytes after transaction");
        }

        return tx;
    }

    /**
     * Validate a length field read from the stream: it must be non-negative
     * and fit in the bytes remaining after {@code offset}.
     */
    private static int lengthField(long value, byte[] data, int offset)
            throws CryptoError {
        if (value < 0 || value > data.length - offset) {
            throw new CryptoError(ERR_TX_PARSE, "Bad length at offset " + offset);
        }
        return (int) value;
    }

    /**
     * Require {@code needed} more bytes from {@code offset}.
     */
    private static void require(byte[] data, int offset, int needed)
            throws CryptoError {
        if (needed > data.length - offset) {
            throw new CryptoError(ERR_TX_PARSE, "Transaction truncated at offset " + offset);
        }
    }

    /**
     * Serialize a TxData structure back to raw (non-witness) transaction bytes.
     *
     * @param tx the transaction to serialize
     * @return raw serialized bytes
     */
    public static byte[] serialize(TxData tx) {
        // Calculate total size
        int size = 4; // version

        byte[] inputCountBytes = CompactSize.write(tx.inputs.length);
        size += inputCountBytes.length;

        // Pre-encode scriptSig lengths
        byte[][] scriptSigLenBytes = new byte[tx.inputs.length][];
        for (int i = 0; i < tx.inputs.length; i++) {
            scriptSigLenBytes[i] = CompactSize.write(tx.inputs[i].scriptSig.length);
            size += 32 + 4; // prevhash + previndex
            size += scriptSigLenBytes[i].length;
            size += tx.inputs[i].scriptSig.length;
            size += 4; // sequence
        }

        byte[] outputCountBytes = CompactSize.write(tx.outputs.length);
        size += outputCountBytes.length;

        // Pre-encode scriptPubKey lengths
        byte[][] scriptPubKeyLenBytes = new byte[tx.outputs.length][];
        for (int i = 0; i < tx.outputs.length; i++) {
            scriptPubKeyLenBytes[i] = CompactSize.write(tx.outputs[i].scriptPubKey.length);
            size += 8; // value
            size += scriptPubKeyLenBytes[i].length;
            size += tx.outputs[i].scriptPubKey.length;
        }

        size += 4; // locktime

        // Build output buffer
        byte[] result = new byte[size];
        int offset = 0;

        // Version
        writeInt32LE(result, offset, tx.version);
        offset += 4;

        // Input count
        System.arraycopy(inputCountBytes, 0, result, offset, inputCountBytes.length);
        offset += inputCountBytes.length;

        // Inputs
        for (int i = 0; i < tx.inputs.length; i++) {
            TxInput input = tx.inputs[i];

            // Previous tx hash
            System.arraycopy(input.prevTxHash, 0, result, offset, 32);
            offset += 32;

            // Previous output index
            writeInt32LE(result, offset, input.prevIndex);
            offset += 4;

            // ScriptSig length + data
            System.arraycopy(scriptSigLenBytes[i], 0, result, offset,
                scriptSigLenBytes[i].length);
            offset += scriptSigLenBytes[i].length;

            System.arraycopy(input.scriptSig, 0, result, offset,
                input.scriptSig.length);
            offset += input.scriptSig.length;

            // Sequence
            writeUInt32LE(result, offset, input.sequence);
            offset += 4;
        }

        // Output count
        System.arraycopy(outputCountBytes, 0, result, offset, outputCountBytes.length);
        offset += outputCountBytes.length;

        // Outputs
        for (int i = 0; i < tx.outputs.length; i++) {
            TxOutput output = tx.outputs[i];

            // Value
            writeInt64LE(result, offset, output.value);
            offset += 8;

            // ScriptPubKey length + data
            System.arraycopy(scriptPubKeyLenBytes[i], 0, result, offset,
                scriptPubKeyLenBytes[i].length);
            offset += scriptPubKeyLenBytes[i].length;

            System.arraycopy(output.scriptPubKey, 0, result, offset,
                output.scriptPubKey.length);
            offset += output.scriptPubKey.length;
        }

        // Locktime
        writeInt32LE(result, offset, tx.locktime);

        return result;
    }

    // ----------------------------------------------------------------
    // Little-endian helpers (package-visible for Bip143Sighash reuse)
    // ----------------------------------------------------------------

    /**
     * Read a signed 32-bit integer in little-endian from data at offset.
     */
    static int readInt32LE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
             | ((data[offset + 1] & 0xFF) << 8)
             | ((data[offset + 2] & 0xFF) << 16)
             | ((data[offset + 3] & 0xFF) << 24);
    }

    /**
     * Read an unsigned 32-bit integer in little-endian as a long.
     */
    static long readUInt32LE(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
             | ((data[offset + 1] & 0xFFL) << 8)
             | ((data[offset + 2] & 0xFFL) << 16)
             | ((data[offset + 3] & 0xFFL) << 24);
    }

    /**
     * Read a signed 64-bit integer in little-endian from data at offset.
     */
    static long readInt64LE(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
             | ((data[offset + 1] & 0xFFL) << 8)
             | ((data[offset + 2] & 0xFFL) << 16)
             | ((data[offset + 3] & 0xFFL) << 24)
             | ((data[offset + 4] & 0xFFL) << 32)
             | ((data[offset + 5] & 0xFFL) << 40)
             | ((data[offset + 6] & 0xFFL) << 48)
             | ((data[offset + 7] & 0xFFL) << 56);
    }

    /**
     * Write a signed 32-bit integer in little-endian to buf at offset.
     */
    static void writeInt32LE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * Write an unsigned 32-bit integer (stored as long) in little-endian.
     */
    static void writeUInt32LE(byte[] buf, int offset, long value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * Write a signed 64-bit integer in little-endian to buf at offset.
     */
    static void writeInt64LE(byte[] buf, int offset, long value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
        buf[offset + 4] = (byte) ((value >> 32) & 0xFF);
        buf[offset + 5] = (byte) ((value >> 40) & 0xFF);
        buf[offset + 6] = (byte) ((value >> 48) & 0xFF);
        buf[offset + 7] = (byte) ((value >> 56) & 0xFF);
    }
}
