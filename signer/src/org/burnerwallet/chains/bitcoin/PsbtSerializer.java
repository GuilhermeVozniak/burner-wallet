package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CompactSize;
import org.burnerwallet.core.CryptoError;

/**
 * Serializes a PsbtTransaction back to BIP174 v0 binary format.
 *
 * Writes the PSBT magic, global map (unsigned tx), per-input maps
 * (witness UTXO, partial sig, sighash type, BIP32 derivation),
 * and per-output maps (BIP32 derivation).
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class PsbtSerializer {

    /** PSBT magic bytes: "psbt" + 0xFF separator. */
    private static final byte[] MAGIC = {
        (byte) 0x70, (byte) 0x73, (byte) 0x62, (byte) 0x74, (byte) 0xFF
    };

    // Key types
    private static final byte GLOBAL_UNSIGNED_TX = 0x00;
    private static final byte INPUT_WITNESS_UTXO = 0x01;
    private static final byte INPUT_PARTIAL_SIG = 0x02;
    private static final byte INPUT_SIGHASH_TYPE = 0x03;
    private static final byte INPUT_BIP32_DERIVATION = 0x06;
    private static final byte OUTPUT_BIP32_DERIVATION = 0x02;

    private PsbtSerializer() {
        // prevent instantiation
    }

    /**
     * Serialize a PsbtTransaction to BIP174 v0 binary format.
     *
     * @param psbt the PSBT to serialize
     * @return the serialized PSBT bytes
     * @throws CryptoError if serialization fails
     */
    public static byte[] serialize(PsbtTransaction psbt) throws CryptoError {
        if (psbt.unsignedTxBytes == null) {
            throw new CryptoError(CryptoError.ERR_PSBT,
                "PSBT missing unsigned transaction bytes");
        }

        // Calculate total size
        int size = MAGIC.length;

        // Global map: unsigned tx
        byte[] txKeyLen = CompactSize.write(1); // key is 1 byte (key type only)
        byte[] txValueLen = CompactSize.write(psbt.unsignedTxBytes.length);
        size += txKeyLen.length + 1 + txValueLen.length + psbt.unsignedTxBytes.length;
        size += 1; // separator 0x00

        // Input maps
        int[] inputSizes = new int[psbt.inputs.length];
        for (int i = 0; i < psbt.inputs.length; i++) {
            inputSizes[i] = calculateInputSize(psbt.inputs[i]);
            size += inputSizes[i];
        }

        // Output maps
        int[] outputSizes = new int[psbt.outputs.length];
        for (int i = 0; i < psbt.outputs.length; i++) {
            outputSizes[i] = calculateOutputSize(psbt.outputs[i]);
            size += outputSizes[i];
        }

        // Build output buffer
        byte[] result = new byte[size];
        int offset = 0;

        // Magic
        System.arraycopy(MAGIC, 0, result, offset, MAGIC.length);
        offset += MAGIC.length;

        // Global map: unsigned tx key-value pair
        // Key: keyLen(1) + keyType(0x00)
        System.arraycopy(txKeyLen, 0, result, offset, txKeyLen.length);
        offset += txKeyLen.length;
        result[offset] = GLOBAL_UNSIGNED_TX;
        offset += 1;
        // Value: valueLen + unsignedTxBytes
        System.arraycopy(txValueLen, 0, result, offset, txValueLen.length);
        offset += txValueLen.length;
        System.arraycopy(psbt.unsignedTxBytes, 0, result, offset,
            psbt.unsignedTxBytes.length);
        offset += psbt.unsignedTxBytes.length;
        // Global separator
        result[offset] = 0x00;
        offset += 1;

        // Input maps
        for (int i = 0; i < psbt.inputs.length; i++) {
            offset = writeInputMap(result, offset, psbt.inputs[i]);
        }

        // Output maps
        for (int i = 0; i < psbt.outputs.length; i++) {
            offset = writeOutputMap(result, offset, psbt.outputs[i]);
        }

        return result;
    }

    /**
     * Calculate the serialized size of an input map (including separator).
     */
    private static int calculateInputSize(PsbtInput input) {
        int size = 0;

        // Witness UTXO (key type 0x01)
        if (input.witnessUtxoScript != null && input.witnessUtxoValue != -1) {
            byte[] scriptLen = CompactSize.write(input.witnessUtxoScript.length);
            int valueSize = 8 + scriptLen.length + input.witnessUtxoScript.length;
            // keyLen(1 byte compact) + keyType(1) + valueLen + value
            size += CompactSize.write(1).length + 1;
            size += CompactSize.write(valueSize).length + valueSize;
        }

        // Partial signature (key type 0x02 + pubkey)
        if (input.partialSigKey != null && input.partialSigValue != null) {
            int keyLen = 1 + input.partialSigKey.length; // keyType + pubkey
            size += CompactSize.write(keyLen).length + keyLen;
            size += CompactSize.write(input.partialSigValue.length).length
                  + input.partialSigValue.length;
        }

        // Sighash type (key type 0x03) — only write if not default SIGHASH_ALL
        if (input.sighashType != Bip143Sighash.SIGHASH_ALL) {
            size += CompactSize.write(1).length + 1; // key
            size += CompactSize.write(4).length + 4; // value (4 bytes LE)
        }

        // BIP32 derivation (key type 0x06 + pubkey)
        if (input.bip32PubKey != null && input.bip32Derivation != null) {
            int keyLen = 1 + input.bip32PubKey.length;
            size += CompactSize.write(keyLen).length + keyLen;
            size += CompactSize.write(input.bip32Derivation.length).length
                  + input.bip32Derivation.length;
        }

        // Separator
        size += 1;

        return size;
    }

    /**
     * Calculate the serialized size of an output map (including separator).
     */
    private static int calculateOutputSize(PsbtOutput output) {
        int size = 0;

        // BIP32 derivation (key type 0x02 + pubkey)
        if (output.bip32PubKey != null && output.bip32Derivation != null) {
            int keyLen = 1 + output.bip32PubKey.length;
            size += CompactSize.write(keyLen).length + keyLen;
            size += CompactSize.write(output.bip32Derivation.length).length
                  + output.bip32Derivation.length;
        }

        // Separator
        size += 1;

        return size;
    }

    /**
     * Write an input map to the buffer at the given offset.
     *
     * @return the new offset after writing
     */
    private static int writeInputMap(byte[] buf, int offset, PsbtInput input) {

        // Witness UTXO (key type 0x01)
        if (input.witnessUtxoScript != null && input.witnessUtxoValue != -1) {
            // Key: length(1) + type(0x01)
            offset = writeKeyValue(buf, offset,
                new byte[] { INPUT_WITNESS_UTXO },
                serializeWitnessUtxo(input));
        }

        // Partial signature (key type 0x02 + pubkey)
        if (input.partialSigKey != null && input.partialSigValue != null) {
            byte[] key = new byte[1 + input.partialSigKey.length];
            key[0] = INPUT_PARTIAL_SIG;
            System.arraycopy(input.partialSigKey, 0, key, 1,
                input.partialSigKey.length);
            offset = writeKeyValue(buf, offset, key, input.partialSigValue);
        }

        // Sighash type (key type 0x03)
        if (input.sighashType != Bip143Sighash.SIGHASH_ALL) {
            byte[] value = new byte[4];
            TxSerializer.writeInt32LE(value, 0, input.sighashType);
            offset = writeKeyValue(buf, offset,
                new byte[] { INPUT_SIGHASH_TYPE }, value);
        }

        // BIP32 derivation (key type 0x06 + pubkey)
        if (input.bip32PubKey != null && input.bip32Derivation != null) {
            byte[] key = new byte[1 + input.bip32PubKey.length];
            key[0] = INPUT_BIP32_DERIVATION;
            System.arraycopy(input.bip32PubKey, 0, key, 1,
                input.bip32PubKey.length);
            offset = writeKeyValue(buf, offset, key, input.bip32Derivation);
        }

        // Separator
        buf[offset] = 0x00;
        offset += 1;

        return offset;
    }

    /**
     * Write an output map to the buffer at the given offset.
     *
     * @return the new offset after writing
     */
    private static int writeOutputMap(byte[] buf, int offset, PsbtOutput output) {

        // BIP32 derivation (key type 0x02 + pubkey)
        if (output.bip32PubKey != null && output.bip32Derivation != null) {
            byte[] key = new byte[1 + output.bip32PubKey.length];
            key[0] = OUTPUT_BIP32_DERIVATION;
            System.arraycopy(output.bip32PubKey, 0, key, 1,
                output.bip32PubKey.length);
            offset = writeKeyValue(buf, offset, key, output.bip32Derivation);
        }

        // Separator
        buf[offset] = 0x00;
        offset += 1;

        return offset;
    }

    /**
     * Write a key-value pair in PSBT format.
     *
     * Format: compactSize(keyLen) + key + compactSize(valueLen) + value
     *
     * @param buf    the output buffer
     * @param offset the current write position
     * @param key    the full key bytes (including key type byte)
     * @param value  the value bytes
     * @return the new offset after writing
     */
    private static int writeKeyValue(byte[] buf, int offset,
            byte[] key, byte[] value) {
        // Key length
        byte[] keyLen = CompactSize.write(key.length);
        System.arraycopy(keyLen, 0, buf, offset, keyLen.length);
        offset += keyLen.length;

        // Key
        System.arraycopy(key, 0, buf, offset, key.length);
        offset += key.length;

        // Value length
        byte[] valueLen = CompactSize.write(value.length);
        System.arraycopy(valueLen, 0, buf, offset, valueLen.length);
        offset += valueLen.length;

        // Value
        System.arraycopy(value, 0, buf, offset, value.length);
        offset += value.length;

        return offset;
    }

    /**
     * Serialize a witness UTXO value field.
     *
     * Format: value (8 bytes LE) + compactSize(scriptPubKey.length) + scriptPubKey
     */
    private static byte[] serializeWitnessUtxo(PsbtInput input) {
        byte[] scriptLen = CompactSize.write(input.witnessUtxoScript.length);
        int size = 8 + scriptLen.length + input.witnessUtxoScript.length;
        byte[] result = new byte[size];
        int off = 0;

        // Value (8 bytes LE)
        TxSerializer.writeInt64LE(result, off, input.witnessUtxoValue);
        off += 8;

        // Script length
        System.arraycopy(scriptLen, 0, result, off, scriptLen.length);
        off += scriptLen.length;

        // Script
        System.arraycopy(input.witnessUtxoScript, 0, result, off,
            input.witnessUtxoScript.length);

        return result;
    }
}
