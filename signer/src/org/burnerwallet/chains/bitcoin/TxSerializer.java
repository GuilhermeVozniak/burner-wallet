package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CompactSize;
import org.burnerwallet.core.CryptoError;

/**
 * Parser and serializer for raw Bitcoin transactions.
 *
 * Handles the standard (non-segwit-witness) serialization format:
 *   version | input_count | inputs... | output_count | outputs... | locktime
 *
 * The LE (little-endian) helper methods are package-visible so that
 * Bip143Sighash can reuse them without duplication.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class TxSerializer {

    private static final int ERR_TX_PARSE = 10;

    /**
     * Parse raw transaction bytes into a TxData structure.
     *
     * @param data the raw serialized transaction
     * @return parsed TxData
     * @throws CryptoError if the data is malformed or truncated
     */
    public static TxData parse(byte[] data) throws CryptoError {
        if (data == null || data.length < 10) {
            throw new CryptoError(ERR_TX_PARSE, "Transaction too short");
        }

        int offset = 0;
        TxData tx = new TxData();

        // Version (4 bytes LE, signed int32)
        tx.version = readInt32LE(data, offset);
        offset += 4;

        // Input count (CompactSize)
        long[] csResult = CompactSize.read(data, offset);
        int inputCount = (int) csResult[0];
        offset += (int) csResult[1];

        // Parse inputs
        tx.inputs = new TxInput[inputCount];
        for (int i = 0; i < inputCount; i++) {
            TxInput input = new TxInput();

            // Previous transaction hash (32 bytes, kept in internal byte order)
            input.prevTxHash = ByteArrayUtils.copyOfRange(data, offset, offset + 32);
            offset += 32;

            // Previous output index (4 bytes LE, uint32 stored as int)
            input.prevIndex = readInt32LE(data, offset);
            offset += 4;

            // ScriptSig length + data
            csResult = CompactSize.read(data, offset);
            int scriptLen = (int) csResult[0];
            offset += (int) csResult[1];

            input.scriptSig = ByteArrayUtils.copyOfRange(data, offset, offset + scriptLen);
            offset += scriptLen;

            // Sequence (4 bytes LE, uint32 stored as long)
            input.sequence = readUInt32LE(data, offset);
            offset += 4;

            tx.inputs[i] = input;
        }

        // Output count (CompactSize)
        csResult = CompactSize.read(data, offset);
        int outputCount = (int) csResult[0];
        offset += (int) csResult[1];

        // Parse outputs
        tx.outputs = new TxOutput[outputCount];
        for (int i = 0; i < outputCount; i++) {
            TxOutput output = new TxOutput();

            // Value (8 bytes LE, int64)
            output.value = readInt64LE(data, offset);
            offset += 8;

            // ScriptPubKey length + data
            csResult = CompactSize.read(data, offset);
            int scriptLen = (int) csResult[0];
            offset += (int) csResult[1];

            output.scriptPubKey = ByteArrayUtils.copyOfRange(data, offset, offset + scriptLen);
            offset += scriptLen;

            tx.outputs[i] = output;
        }

        // Locktime (4 bytes LE, int32)
        tx.locktime = readInt32LE(data, offset);

        return tx;
    }

    /**
     * Serialize a TxData structure back to raw transaction bytes.
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
