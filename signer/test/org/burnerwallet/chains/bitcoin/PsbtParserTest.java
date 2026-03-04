package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for PsbtParser (BIP174 v0 PSBT binary format).
 *
 * Test PSBTs are constructed manually byte-by-byte to avoid
 * circular dependencies on a PSBT serializer.
 */
public class PsbtParserTest {

    /** PSBT magic: "psbt" + 0xFF */
    private static final String MAGIC = "70736274ff";

    /**
     * Minimal unsigned transaction: version=2, 1 input (all-zero prevhash,
     * index 0, empty scriptSig, sequence 0xFFFFFFFF), 1 output (1000 sats,
     * P2WPKH script 0014 + 20 zero bytes), locktime=0.
     */
    private static final String UNSIGNED_TX_HEX =
        "02000000"                                                      // version 2
        + "01"                                                          // 1 input
        + "0000000000000000000000000000000000000000000000000000000000000000" // prevhash
        + "00000000"                                                    // prev index 0
        + "00"                                                          // empty scriptSig
        + "ffffffff"                                                    // sequence
        + "01"                                                          // 1 output
        + "e803000000000000"                                            // 1000 sats
        + "16"                                                          // scriptPubKey len 22
        + "00140000000000000000000000000000000000000000"                 // P2WPKH
        + "00000000";                                                   // locktime 0

    /**
     * Two-input, two-output unsigned transaction for fee calculation test.
     *
     * version=2, 2 inputs, 2 outputs (90000 + 5000 sats), locktime=0.
     */
    private static final String TWO_IN_TWO_OUT_TX_HEX =
        "02000000"                                                      // version 2
        + "02"                                                          // 2 inputs
        // Input 0
        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        + "00000000"
        + "00"
        + "ffffffff"
        // Input 1
        + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        + "01000000"
        + "00"
        + "ffffffff"
        // Outputs
        + "02"                                                          // 2 outputs
        + "905f010000000000"                                            // 90000 sats
        + "16"
        + "0014cccccccccccccccccccccccccccccccccccccccc"
        + "8813000000000000"                                            // 5000 sats
        + "16"
        + "0014dddddddddddddddddddddddddddddddddddddddd"
        + "00000000";                                                   // locktime 0

    // ---------------------------------------------------------------
    // Helper: build a PSBT global map entry
    // ---------------------------------------------------------------

    /**
     * Build a global map containing the unsigned transaction, terminated by 0x00.
     */
    private static String buildGlobalMap(String unsignedTxHex) throws CryptoError {
        // Key: length=1, type=0x00
        // Value: length(CompactSize) + tx bytes
        byte[] txBytes = HexCodec.decode(unsignedTxHex);
        String valueLenHex = HexCodec.encode(
            org.burnerwallet.core.CompactSize.write(txBytes.length));
        return "01" + "00" + valueLenHex + unsignedTxHex + "00";
    }

    /**
     * Build an empty map (just the separator byte).
     */
    private static String emptyMap() {
        return "00";
    }

    // ---------------------------------------------------------------
    // Test 1: Invalid magic bytes throw CryptoError with ERR_PSBT
    // ---------------------------------------------------------------

    @Test
    public void parseMagicBytes() {
        // Corrupt magic: "psXt" + 0xFF
        byte[] badMagic = new byte[] {
            0x70, 0x73, 0x58, 0x74, (byte) 0xFF,
            0x00  // empty global map
        };
        try {
            PsbtParser.parse(badMagic);
            fail("Expected CryptoError for invalid magic");
        } catch (CryptoError e) {
            assertEquals(CryptoError.ERR_PSBT, e.getErrorCode());
        }

        // Too short
        byte[] tooShort = new byte[] { 0x70, 0x73 };
        try {
            PsbtParser.parse(tooShort);
            fail("Expected CryptoError for truncated magic");
        } catch (CryptoError e) {
            assertEquals(CryptoError.ERR_PSBT, e.getErrorCode());
        }

        // Null
        try {
            PsbtParser.parse(null);
            fail("Expected CryptoError for null input");
        } catch (CryptoError e) {
            assertEquals(CryptoError.ERR_PSBT, e.getErrorCode());
        }
    }

    // ---------------------------------------------------------------
    // Test 2: Minimal valid PSBT with empty input/output maps
    // ---------------------------------------------------------------

    @Test
    public void parseMinimalPsbt() throws CryptoError {
        String psbtHex = MAGIC
            + buildGlobalMap(UNSIGNED_TX_HEX)
            + emptyMap()   // input 0 map (empty)
            + emptyMap();  // output 0 map (empty)

        byte[] psbtBytes = HexCodec.decode(psbtHex);
        PsbtTransaction psbt = PsbtParser.parse(psbtBytes);

        assertNotNull(psbt.unsignedTx);
        assertNotNull(psbt.unsignedTxBytes);
        assertEquals(2, psbt.unsignedTx.version);
        assertEquals(1, psbt.unsignedTx.inputs.length);
        assertEquals(1, psbt.unsignedTx.outputs.length);
        assertEquals(1000L, psbt.unsignedTx.outputs[0].value);

        // Input/output arrays match tx counts
        assertEquals(1, psbt.inputs.length);
        assertEquals(1, psbt.outputs.length);

        // No witness UTXO set
        assertEquals(-1L, psbt.inputs[0].witnessUtxoValue);
        assertNull(psbt.inputs[0].witnessUtxoScript);

        // Default sighash
        assertEquals(0x01, psbt.inputs[0].sighashType);
    }

    // ---------------------------------------------------------------
    // Test 3: PSBT with WITNESS_UTXO in input map
    // ---------------------------------------------------------------

    @Test
    public void parseWithWitnessUtxo() throws CryptoError {
        // Build witness UTXO value field:
        // value (8 bytes LE) + scriptPubKey_length (CompactSize) + scriptPubKey
        //
        // value = 50000 sats = 0x000000000000C350 LE = 50c3000000000000
        // scriptPubKey = 0014 + 20 bytes of 0xAA (P2WPKH, 22 bytes)
        String witnessUtxoHex =
            "50c3000000000000"                                          // 50000 sats LE
            + "16"                                                      // scriptPubKey len 22
            + "0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";            // P2WPKH script

        byte[] witnessUtxoBytes = HexCodec.decode(witnessUtxoHex);
        String witnessUtxoLenHex = HexCodec.encode(
            org.burnerwallet.core.CompactSize.write(witnessUtxoBytes.length));

        // Input map: key(type=0x01) + value(witnessUtxo) + separator
        String inputMap = "01" + "01"               // keyLen=1, keyType=0x01
            + witnessUtxoLenHex + witnessUtxoHex    // value
            + "00";                                 // separator

        String psbtHex = MAGIC
            + buildGlobalMap(UNSIGNED_TX_HEX)
            + inputMap
            + emptyMap();  // output 0

        byte[] psbtBytes = HexCodec.decode(psbtHex);
        PsbtTransaction psbt = PsbtParser.parse(psbtBytes);

        // Verify witness UTXO was parsed correctly
        assertEquals(50000L, psbt.inputs[0].witnessUtxoValue);
        assertNotNull(psbt.inputs[0].witnessUtxoScript);
        assertEquals(22, psbt.inputs[0].witnessUtxoScript.length);

        // Verify scriptPubKey starts with 0x00 0x14
        assertEquals(0x00, psbt.inputs[0].witnessUtxoScript[0]);
        assertEquals(0x14, psbt.inputs[0].witnessUtxoScript[1]);

        // Verify remaining bytes are 0xAA
        for (int i = 2; i < 22; i++) {
            assertEquals((byte) 0xAA, psbt.inputs[0].witnessUtxoScript[i]);
        }
    }

    // ---------------------------------------------------------------
    // Test 4: Unknown global key types are silently skipped
    // ---------------------------------------------------------------

    @Test
    public void parseIgnoresUnknownKeys() throws CryptoError {
        // Build a global map with an unknown key type 0xFC before the tx entry
        // Unknown entry: keyLen=1, keyType=0xFC, valueLen=2, value=0xBEEF
        String unknownEntry = "01" + "fc" + "02" + "beef";

        // Global map: unknown entry + tx entry + separator
        byte[] txBytes = HexCodec.decode(UNSIGNED_TX_HEX);
        String valueLenHex = HexCodec.encode(
            org.burnerwallet.core.CompactSize.write(txBytes.length));
        String globalMap = unknownEntry
            + "01" + "00" + valueLenHex + UNSIGNED_TX_HEX
            + "00";

        String psbtHex = MAGIC + globalMap + emptyMap() + emptyMap();

        byte[] psbtBytes = HexCodec.decode(psbtHex);
        PsbtTransaction psbt = PsbtParser.parse(psbtBytes);

        // Should parse successfully despite unknown key
        assertNotNull(psbt.unsignedTx);
        assertEquals(2, psbt.unsignedTx.version);
        assertEquals(1, psbt.inputs.length);
        assertEquals(1, psbt.outputs.length);
    }

    // ---------------------------------------------------------------
    // Test 5: Fee calculation = inputValue - outputValue
    // ---------------------------------------------------------------

    @Test
    public void feeCalculation() throws CryptoError {
        // Two inputs with witness UTXOs: 50000 + 55000 = 105000 sats
        // Two outputs: 90000 + 5000 = 95000 sats
        // Fee = 105000 - 95000 = 10000 sats

        // Witness UTXO for input 0: 50000 sats, P2WPKH script
        String witnessUtxo0 =
            "50c3000000000000"
            + "16"
            + "0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        byte[] wu0 = HexCodec.decode(witnessUtxo0);
        String wu0Len = HexCodec.encode(
            org.burnerwallet.core.CompactSize.write(wu0.length));

        // Witness UTXO for input 1: 55000 sats (0xD6D8 = 55000)
        // 55000 = 0x0000_0000_0000_D6D8 LE = d8d6000000000000
        String witnessUtxo1 =
            "d8d6000000000000"
            + "16"
            + "0014bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        byte[] wu1 = HexCodec.decode(witnessUtxo1);
        String wu1Len = HexCodec.encode(
            org.burnerwallet.core.CompactSize.write(wu1.length));

        // Input maps
        String inputMap0 = "01" + "01" + wu0Len + witnessUtxo0 + "00";
        String inputMap1 = "01" + "01" + wu1Len + witnessUtxo1 + "00";

        String psbtHex = MAGIC
            + buildGlobalMap(TWO_IN_TWO_OUT_TX_HEX)
            + inputMap0
            + inputMap1
            + emptyMap()   // output 0
            + emptyMap();  // output 1

        byte[] psbtBytes = HexCodec.decode(psbtHex);
        PsbtTransaction psbt = PsbtParser.parse(psbtBytes);

        // Verify input values
        assertEquals(50000L, psbt.inputs[0].witnessUtxoValue);
        assertEquals(55000L, psbt.inputs[1].witnessUtxoValue);

        // Verify output values
        assertEquals(90000L, psbt.unsignedTx.outputs[0].value);
        assertEquals(5000L, psbt.unsignedTx.outputs[1].value);

        // Verify totals and fee
        assertEquals(105000L, psbt.getTotalInputValue());
        assertEquals(95000L, psbt.getTotalOutputValue());
        assertEquals(10000L, psbt.getFee());
    }
}
