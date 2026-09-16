package org.burnerwallet.chains.bitcoin;

import java.util.Vector;

import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CompactSize;
import org.burnerwallet.core.CryptoError;

/**
 * Streaming BIP174 v0 PSBT parser.
 *
 * Extracts fields needed for signing P2WPKH transactions:
 * - Global: unsigned transaction (key type 0x00)
 * - Per-input: non-witness UTXO, witness UTXO, sighash type,
 *   BIP32 derivation, partial signatures
 * - Per-output: BIP32 derivation
 *
 * Key/value pairs the signer does not interpret are preserved on the
 * parsed objects so that {@link PsbtSerializer} can re-emit them, as
 * BIP174 requires. Duplicate keys within a map are rejected (BIP174).
 *
 * Every length read from the input is validated against the remaining
 * bytes before any allocation, so malformed data fails with
 * {@link CryptoError} rather than exhausting the device's heap.
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

    /** One parsed key-value pair. */
    private static final class Pair {
        byte[] key;
        byte[] value;
    }

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
        Vector seen = new Vector();
        while (true) {
            Pair p = readPair(data, offsetHolder);
            if (p == null) {
                return;
            }
            rejectDuplicate(seen, p.key);

            int keyType = p.key[0] & 0xFF;
            if (keyType == GLOBAL_UNSIGNED_TX && p.key.length == 1) {
                psbt.unsignedTxBytes = p.value;
                psbt.unsignedTx = TxSerializer.parse(p.value);
            } else {
                psbt.unknown.addElement(new byte[][] { p.key, p.value });
            }
        }
    }

    /**
     * Parse a single input key-value map.
     */
    private static void parseInputMap(byte[] data, int[] offsetHolder,
            PsbtInput input) throws CryptoError {
        Vector seen = new Vector();
        while (true) {
            Pair p = readPair(data, offsetHolder);
            if (p == null) {
                return;
            }
            rejectDuplicate(seen, p.key);

            int keyType = p.key[0] & 0xFF;
            int keyDataLen = p.key.length - 1;

            if (keyType == INPUT_NON_WITNESS_UTXO && keyDataLen == 0) {
                input.nonWitnessUtxo = p.value;
            } else if (keyType == INPUT_WITNESS_UTXO && keyDataLen == 0) {
                parseWitnessUtxo(p.value, input);
            } else if (keyType == INPUT_PARTIAL_SIG && keyDataLen > 0
                    && input.partialSigKey == null) {
                input.partialSigKey = ByteArrayUtils.copyOfRange(p.key, 1, p.key.length);
                input.partialSigValue = p.value;
            } else if (keyType == INPUT_SIGHASH_TYPE && keyDataLen == 0) {
                // BIP174: the value is exactly a 32-bit little-endian integer
                if (p.value.length != 4) {
                    throw new CryptoError(CryptoError.ERR_PSBT,
                        "Bad sighash type length: " + p.value.length);
                }
                input.sighashType = TxSerializer.readInt32LE(p.value, 0);
            } else if (keyType == INPUT_BIP32_DERIVATION && keyDataLen > 0
                    && input.bip32PubKey == null) {
                input.bip32PubKey = ByteArrayUtils.copyOfRange(p.key, 1, p.key.length);
                input.bip32Derivation = p.value;
            } else {
                // Unknown types, and additional partial sigs / derivations
                // for other keys, are preserved verbatim.
                input.unknown.addElement(new byte[][] { p.key, p.value });
            }
        }
    }

    /**
     * Parse a WITNESS_UTXO value field.
     *
     * Format: value (8 bytes LE) + scriptPubKey_length (CompactSize) + scriptPubKey.
     * The script must end exactly at the end of the value field.
     */
    private static void parseWitnessUtxo(byte[] value, PsbtInput input)
            throws CryptoError {
        if (value.length < 9) {
            throw new CryptoError(CryptoError.ERR_PSBT,
                "Witness UTXO value too short");
        }

        long amount = TxSerializer.readInt64LE(value, 0);
        if (amount < 0) {
            throw new CryptoError(CryptoError.ERR_PSBT,
                "Witness UTXO amount is negative");
        }

        long[] csResult = CompactSize.readChecked(value, 8);
        int scriptStart = 8 + (int) csResult[1];
        if (csResult[0] < 0 || csResult[0] != value.length - scriptStart) {
            throw new CryptoError(CryptoError.ERR_PSBT,
                "Witness UTXO script length mismatch");
        }

        input.witnessUtxoValue = amount;
        input.witnessUtxoScript = ByteArrayUtils.copyOfRange(
            value, scriptStart, value.length);
    }

    /**
     * Parse a single output key-value map.
     */
    private static void parseOutputMap(byte[] data, int[] offsetHolder,
            PsbtOutput output) throws CryptoError {
        Vector seen = new Vector();
        while (true) {
            Pair p = readPair(data, offsetHolder);
            if (p == null) {
                return;
            }
            rejectDuplicate(seen, p.key);

            int keyType = p.key[0] & 0xFF;
            int keyDataLen = p.key.length - 1;

            if (keyType == OUTPUT_BIP32_DERIVATION && keyDataLen > 0
                    && output.bip32PubKey == null) {
                output.bip32PubKey = ByteArrayUtils.copyOfRange(p.key, 1, p.key.length);
                output.bip32Derivation = p.value;
            } else {
                output.unknown.addElement(new byte[][] { p.key, p.value });
            }
        }
    }

    /**
     * Read one key-value pair at offsetHolder[0], advancing it.
     *
     * @return the pair, or null if the map separator (key length 0) was read
     */
    private static Pair readPair(byte[] data, int[] offsetHolder) throws CryptoError {
        int offset = offsetHolder[0];

        long[] csResult = CompactSize.readChecked(data, offset);
        offset += (int) csResult[1];
        int keyLen = lengthField(csResult[0], data, offset);

        if (keyLen == 0) {
            offsetHolder[0] = offset;
            return null;
        }

        byte[] key = ByteArrayUtils.copyOfRange(data, offset, offset + keyLen);
        offset += keyLen;

        csResult = CompactSize.readChecked(data, offset);
        offset += (int) csResult[1];
        int valueLen = lengthField(csResult[0], data, offset);

        byte[] value = ByteArrayUtils.copyOfRange(data, offset, offset + valueLen);
        offset += valueLen;

        offsetHolder[0] = offset;
        Pair p = new Pair();
        p.key = key;
        p.value = value;
        return p;
    }

    /**
     * Validate a length field: non-negative and within the bytes remaining
     * after {@code offset}. Written to be immune to int overflow.
     */
    private static int lengthField(long value, byte[] data, int offset)
            throws CryptoError {
        if (offset < 0 || offset > data.length
                || value < 0 || value > data.length - offset) {
            throw new CryptoError(CryptoError.ERR_PSBT,
                "PSBT data truncated at offset " + offset);
        }
        return (int) value;
    }

    /**
     * BIP174: a map must not contain the same key twice.
     */
    private static void rejectDuplicate(Vector seen, byte[] key) throws CryptoError {
        for (int i = 0; i < seen.size(); i++) {
            if (ByteArrayUtils.constantTimeEquals((byte[]) seen.elementAt(i), key)) {
                throw new CryptoError(CryptoError.ERR_PSBT,
                    "Duplicate key in PSBT map (type 0x"
                    + Integer.toHexString(key[0] & 0xFF) + ")");
            }
        }
        seen.addElement(key);
    }
}
