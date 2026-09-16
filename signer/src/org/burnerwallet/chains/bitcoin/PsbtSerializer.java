package org.burnerwallet.chains.bitcoin;

import java.io.ByteArrayOutputStream;
import java.util.Vector;

import org.burnerwallet.core.CompactSize;
import org.burnerwallet.core.CryptoError;

/**
 * Serializes a PsbtTransaction back to BIP174 v0 binary format.
 *
 * Writes the PSBT magic, global map (unsigned tx + preserved unknown
 * pairs), per-input maps (non-witness UTXO, witness UTXO, partial sig,
 * sighash type, BIP32 derivation, preserved unknown pairs), and
 * per-output maps (BIP32 derivation, preserved unknown pairs).
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
    private static final byte INPUT_NON_WITNESS_UTXO = 0x00;
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

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Magic
        out.write(MAGIC, 0, MAGIC.length);

        // Global map
        writeKeyValue(out, new byte[] { GLOBAL_UNSIGNED_TX }, psbt.unsignedTxBytes);
        writeUnknown(out, psbt.unknown);
        out.write(0x00);

        // Input maps
        for (int i = 0; i < psbt.inputs.length; i++) {
            writeInputMap(out, psbt.inputs[i]);
        }

        // Output maps
        for (int i = 0; i < psbt.outputs.length; i++) {
            writeOutputMap(out, psbt.outputs[i]);
        }

        return out.toByteArray();
    }

    /**
     * Write an input map (including separator).
     */
    private static void writeInputMap(ByteArrayOutputStream out, PsbtInput input) {
        // Non-witness UTXO (key type 0x00)
        if (input.nonWitnessUtxo != null) {
            writeKeyValue(out, new byte[] { INPUT_NON_WITNESS_UTXO },
                input.nonWitnessUtxo);
        }

        // Witness UTXO (key type 0x01)
        if (input.witnessUtxoScript != null && input.witnessUtxoValue != -1) {
            writeKeyValue(out, new byte[] { INPUT_WITNESS_UTXO },
                serializeWitnessUtxo(input));
        }

        // Partial signature (key type 0x02 + pubkey)
        if (input.partialSigKey != null && input.partialSigValue != null) {
            writeKeyValue(out, prefixed(INPUT_PARTIAL_SIG, input.partialSigKey),
                input.partialSigValue);
        }

        // Sighash type (key type 0x03) -- only write if not default SIGHASH_ALL
        if (input.sighashType != Bip143Sighash.SIGHASH_ALL) {
            byte[] value = new byte[4];
            TxSerializer.writeInt32LE(value, 0, input.sighashType);
            writeKeyValue(out, new byte[] { INPUT_SIGHASH_TYPE }, value);
        }

        // BIP32 derivation (key type 0x06 + pubkey)
        if (input.bip32PubKey != null && input.bip32Derivation != null) {
            writeKeyValue(out, prefixed(INPUT_BIP32_DERIVATION, input.bip32PubKey),
                input.bip32Derivation);
        }

        // Preserved pairs
        writeUnknown(out, input.unknown);

        // Separator
        out.write(0x00);
    }

    /**
     * Write an output map (including separator).
     */
    private static void writeOutputMap(ByteArrayOutputStream out, PsbtOutput output) {
        // BIP32 derivation (key type 0x02 + pubkey)
        if (output.bip32PubKey != null && output.bip32Derivation != null) {
            writeKeyValue(out, prefixed(OUTPUT_BIP32_DERIVATION, output.bip32PubKey),
                output.bip32Derivation);
        }

        writeUnknown(out, output.unknown);

        // Separator
        out.write(0x00);
    }

    /**
     * Write preserved unknown key-value pairs.
     */
    private static void writeUnknown(ByteArrayOutputStream out, Vector unknown) {
        if (unknown == null) {
            return;
        }
        for (int i = 0; i < unknown.size(); i++) {
            byte[][] pair = (byte[][]) unknown.elementAt(i);
            writeKeyValue(out, pair[0], pair[1]);
        }
    }

    /**
     * Build a key: type byte followed by key data.
     */
    private static byte[] prefixed(byte keyType, byte[] keyData) {
        byte[] key = new byte[1 + keyData.length];
        key[0] = keyType;
        System.arraycopy(keyData, 0, key, 1, keyData.length);
        return key;
    }

    /**
     * Write a key-value pair in PSBT format:
     * compactSize(keyLen) + key + compactSize(valueLen) + value
     */
    private static void writeKeyValue(ByteArrayOutputStream out,
            byte[] key, byte[] value) {
        byte[] keyLen = CompactSize.write(key.length);
        out.write(keyLen, 0, keyLen.length);
        out.write(key, 0, key.length);

        byte[] valueLen = CompactSize.write(value.length);
        out.write(valueLen, 0, valueLen.length);
        out.write(value, 0, value.length);
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
