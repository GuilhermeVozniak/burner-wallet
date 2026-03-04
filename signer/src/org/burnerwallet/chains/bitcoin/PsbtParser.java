package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CompactSize;
import org.burnerwallet.core.CryptoError;

/**
 * Streaming BIP174 v0 PSBT parser.
 *
 * Extracts fields needed for signing P2WPKH transactions:
 * - Global: unsigned transaction (key type 0x00)
 * - Per-input: witness UTXO, non-witness UTXO, sighash type,
 *   BIP32 derivation, partial signatures
 * - Per-output: BIP32 derivation
 *
 * Unknown key types are silently skipped, making the parser
 * forward-compatible with PSBT extensions.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class PsbtParser {

    /** PSBT magic bytes: "psbt" + 0xFF separator. */
    private static final byte[] MAGIC = {
        (byte) 0x70, (byte) 0x73, (byte) 0x62, (byte) 0x74, (byte) 0xFF
    };

    // Global key types
    private static final int GLOBAL_UNSIGNED_TX = 0x00;

    // Input key types
    private static final int INPUT_NON_WITNESS_UTXO = 0x00;
    private static final int INPUT_WITNESS_UTXO = 0x01;
    private static final int INPUT_PARTIAL_SIG = 0x02;
    private static final int INPUT_SIGHASH_TYPE = 0x03;
    private static final int INPUT_BIP32_DERIVATION = 0x06;

    // Output key types
    private static final int OUTPUT_BIP32_DERIVATION = 0x02;

    /**
     * Parse a BIP174 v0 PSBT from raw bytes.
     *
     * @param data the complete PSBT binary data
     * @return parsed PsbtTransaction
     * @throws CryptoError if the data is malformed
     */
    public static PsbtTransaction parse(byte[] data) throws CryptoError {
        if (data == null || data.length < MAGIC.length) {
            throw new CryptoError(CryptoError.ERR_PSBT, "PSBT data too short");
        }

        // Validate magic bytes
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Invalid PSBT magic bytes");
            }
        }

        int[] offsetHolder = new int[] { MAGIC.length };
        PsbtTransaction psbt = new PsbtTransaction();

        // --- Global map ---
        parseGlobalMap(data, offsetHolder, psbt);

        if (psbt.unsignedTx == null) {
            throw new CryptoError(CryptoError.ERR_PSBT,
                "PSBT missing unsigned transaction");
        }

        int inputCount = psbt.unsignedTx.inputs.length;
        int outputCount = psbt.unsignedTx.outputs.length;

        // --- Input maps ---
        psbt.inputs = new PsbtInput[inputCount];
        for (int i = 0; i < inputCount; i++) {
            psbt.inputs[i] = new PsbtInput();
            parseInputMap(data, offsetHolder, psbt.inputs[i]);
        }

        // --- Output maps ---
        psbt.outputs = new PsbtOutput[outputCount];
        for (int i = 0; i < outputCount; i++) {
            psbt.outputs[i] = new PsbtOutput();
            parseOutputMap(data, offsetHolder, psbt.outputs[i]);
        }

        return psbt;
    }

    /**
     * Parse the global key-value map.
     */
    private static void parseGlobalMap(byte[] data, int[] offsetHolder,
            PsbtTransaction psbt) throws CryptoError {
        while (true) {
            int offset = offsetHolder[0];
            checkBounds(data, offset, 1);

            // Read key length
            long[] csResult = CompactSize.read(data, offset);
            long keyLen = csResult[0];
            offset += (int) csResult[1];

            // Separator byte (keyLen == 0) terminates the map
            if (keyLen == 0) {
                offsetHolder[0] = offset;
                return;
            }

            checkBounds(data, offset, (int) keyLen);

            // Key type is the first byte of the key
            int keyType = data[offset] & 0xFF;
            // Key data follows the type byte (keyLen - 1 bytes)
            int keyDataLen = (int) keyLen - 1;
            int keyDataStart = offset + 1;
            offset += (int) keyLen;

            // Read value length
            checkBounds(data, offset, 1);
            csResult = CompactSize.read(data, offset);
            long valueLen = csResult[0];
            offset += (int) csResult[1];

            checkBounds(data, offset, (int) valueLen);
            int valueStart = offset;
            offset += (int) valueLen;

            // Process known key types
            if (keyType == GLOBAL_UNSIGNED_TX) {
                psbt.unsignedTxBytes = ByteArrayUtils.copyOfRange(
                    data, valueStart, valueStart + (int) valueLen);
                psbt.unsignedTx = TxSerializer.parse(psbt.unsignedTxBytes);
            }
            // Unknown key types are silently skipped

            offsetHolder[0] = offset;
        }
    }

    /**
     * Parse a single input key-value map.
     */
    private static void parseInputMap(byte[] data, int[] offsetHolder,
            PsbtInput input) throws CryptoError {
        while (true) {
            int offset = offsetHolder[0];
            checkBounds(data, offset, 1);

            // Read key length
            long[] csResult = CompactSize.read(data, offset);
            long keyLen = csResult[0];
            offset += (int) csResult[1];

            // Separator
            if (keyLen == 0) {
                offsetHolder[0] = offset;
                return;
            }

            checkBounds(data, offset, (int) keyLen);

            int keyType = data[offset] & 0xFF;
            int keyDataLen = (int) keyLen - 1;
            int keyDataStart = offset + 1;
            offset += (int) keyLen;

            // Read value length
            checkBounds(data, offset, 1);
            csResult = CompactSize.read(data, offset);
            long valueLen = csResult[0];
            offset += (int) csResult[1];

            checkBounds(data, offset, (int) valueLen);
            int valueStart = offset;
            offset += (int) valueLen;

            // Process known input key types
            if (keyType == INPUT_NON_WITNESS_UTXO) {
                input.nonWitnessUtxo = ByteArrayUtils.copyOfRange(
                    data, valueStart, valueStart + (int) valueLen);
            } else if (keyType == INPUT_WITNESS_UTXO) {
                parseWitnessUtxo(data, valueStart, (int) valueLen, input);
            } else if (keyType == INPUT_PARTIAL_SIG) {
                // Key data = pubkey
                if (keyDataLen > 0) {
                    input.partialSigKey = ByteArrayUtils.copyOfRange(
                        data, keyDataStart, keyDataStart + keyDataLen);
                }
                input.partialSigValue = ByteArrayUtils.copyOfRange(
                    data, valueStart, valueStart + (int) valueLen);
            } else if (keyType == INPUT_SIGHASH_TYPE) {
                if (valueLen >= 4) {
                    input.sighashType = TxSerializer.readInt32LE(data, valueStart);
                }
            } else if (keyType == INPUT_BIP32_DERIVATION) {
                // Key data = pubkey
                if (keyDataLen > 0) {
                    input.bip32PubKey = ByteArrayUtils.copyOfRange(
                        data, keyDataStart, keyDataStart + keyDataLen);
                }
                input.bip32Derivation = ByteArrayUtils.copyOfRange(
                    data, valueStart, valueStart + (int) valueLen);
            }
            // Unknown key types are silently skipped

            offsetHolder[0] = offset;
        }
    }

    /**
     * Parse a WITNESS_UTXO value field.
     *
     * Format: value (8 bytes LE) + scriptPubKey_length (CompactSize) + scriptPubKey
     */
    private static void parseWitnessUtxo(byte[] data, int valueStart,
            int valueLen, PsbtInput input) throws CryptoError {
        if (valueLen < 9) {
            throw new CryptoError(CryptoError.ERR_PSBT,
                "Witness UTXO value too short");
        }

        int off = valueStart;

        // Value: 8 bytes LE
        input.witnessUtxoValue = TxSerializer.readInt64LE(data, off);
        off += 8;

        // ScriptPubKey length (CompactSize)
        long[] csResult = CompactSize.read(data, off);
        int scriptLen = (int) csResult[0];
        off += (int) csResult[1];

        // ScriptPubKey
        input.witnessUtxoScript = ByteArrayUtils.copyOfRange(
            data, off, off + scriptLen);
    }

    /**
     * Parse a single output key-value map.
     */
    private static void parseOutputMap(byte[] data, int[] offsetHolder,
            PsbtOutput output) throws CryptoError {
        while (true) {
            int offset = offsetHolder[0];
            checkBounds(data, offset, 1);

            // Read key length
            long[] csResult = CompactSize.read(data, offset);
            long keyLen = csResult[0];
            offset += (int) csResult[1];

            // Separator
            if (keyLen == 0) {
                offsetHolder[0] = offset;
                return;
            }

            checkBounds(data, offset, (int) keyLen);

            int keyType = data[offset] & 0xFF;
            int keyDataLen = (int) keyLen - 1;
            int keyDataStart = offset + 1;
            offset += (int) keyLen;

            // Read value length
            checkBounds(data, offset, 1);
            csResult = CompactSize.read(data, offset);
            long valueLen = csResult[0];
            offset += (int) csResult[1];

            checkBounds(data, offset, (int) valueLen);
            int valueStart = offset;
            offset += (int) valueLen;

            // Process known output key types
            if (keyType == OUTPUT_BIP32_DERIVATION) {
                // Key data = pubkey
                if (keyDataLen > 0) {
                    output.bip32PubKey = ByteArrayUtils.copyOfRange(
                        data, keyDataStart, keyDataStart + keyDataLen);
                }
                output.bip32Derivation = ByteArrayUtils.copyOfRange(
                    data, valueStart, valueStart + (int) valueLen);
            }
            // Unknown key types are silently skipped

            offsetHolder[0] = offset;
        }
    }

    /**
     * Check that at least {@code needed} bytes remain from {@code offset}.
     */
    private static void checkBounds(byte[] data, int offset, int needed)
            throws CryptoError {
        if (offset + needed > data.length) {
            throw new CryptoError(CryptoError.ERR_PSBT,
                "PSBT data truncated at offset " + offset);
        }
    }
}
