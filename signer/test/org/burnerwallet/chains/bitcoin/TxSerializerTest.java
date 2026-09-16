package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for TxSerializer (raw Bitcoin transaction parse/serialize).
 */
public class TxSerializerTest {

    /**
     * 1-in-1-out unsigned transaction.
     *
     * version=2, 1 input (32-byte zero prevhash, index 0, empty scriptSig,
     * sequence 0xFFFFFFFF), 1 output (1000 sats, P2WPKH script), locktime=0.
     */
    private static final String ONE_IN_ONE_OUT_HEX =
        "02000000"                                                  // version 2
        + "01"                                                      // 1 input
        + "0000000000000000000000000000000000000000000000000000000000000000" // prevhash
        + "00000000"                                                // prev index 0
        + "00"                                                      // empty scriptSig
        + "ffffffff"                                                // sequence
        + "01"                                                      // 1 output
        + "e803000000000000"                                        // 1000 sats
        + "16"                                                      // scriptPubKey len 22
        + "00140000000000000000000000000000000000000000"             // P2WPKH
        + "00000000";                                               // locktime 0

    /**
     * 2-in-1-out unsigned transaction.
     *
     * version=1, 2 inputs, 1 output (50000 sats), locktime=10.
     */
    private static final String TWO_IN_ONE_OUT_HEX =
        "01000000"                                                  // version 1
        + "02"                                                      // 2 inputs
        // Input 0
        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // prevhash
        + "00000000"                                                // prev index 0
        + "00"                                                      // empty scriptSig
        + "feffffff"                                                // sequence 0xFFFFFFFE
        // Input 1
        + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" // prevhash
        + "01000000"                                                // prev index 1
        + "00"                                                      // empty scriptSig
        + "feffffff"                                                // sequence 0xFFFFFFFE
        // Outputs
        + "01"                                                      // 1 output
        + "50c3000000000000"                                        // 50000 sats
        + "16"                                                      // scriptPubKey len 22
        + "0014cccccccccccccccccccccccccccccccccccccccc"             // P2WPKH
        + "0a000000";                                               // locktime 10

    @Test
    public void parseUnsignedTx() throws CryptoError {
        byte[] raw = HexCodec.decode(ONE_IN_ONE_OUT_HEX);
        TxData tx = TxSerializer.parse(raw);

        assertEquals(2, tx.version);
        assertEquals(1, tx.inputs.length);
        assertEquals(1, tx.outputs.length);
        assertEquals(0, tx.locktime);
        assertEquals(1000L, tx.outputs[0].value);
    }

    @Test
    public void parseAndSerializeRoundTrip() throws CryptoError {
        byte[] raw = HexCodec.decode(ONE_IN_ONE_OUT_HEX);
        TxData tx = TxSerializer.parse(raw);
        byte[] serialized = TxSerializer.serialize(tx);

        assertEquals(ONE_IN_ONE_OUT_HEX, HexCodec.encode(serialized));
    }

    @Test
    public void parseTwoInputs() throws CryptoError {
        byte[] raw = HexCodec.decode(TWO_IN_ONE_OUT_HEX);
        TxData tx = TxSerializer.parse(raw);

        assertEquals(1, tx.version);
        assertEquals(2, tx.inputs.length);
        assertEquals(1, tx.outputs.length);
        assertEquals(10, tx.locktime);

        // Input 0: prev index 0, sequence 0xFFFFFFFE
        assertEquals(0, tx.inputs[0].prevIndex);
        assertEquals(0xFFFFFFFEL, tx.inputs[0].sequence);

        // Input 1: prev index 1, sequence 0xFFFFFFFE
        assertEquals(1, tx.inputs[1].prevIndex);
        assertEquals(0xFFFFFFFEL, tx.inputs[1].sequence);

        // Output: 50000 sats
        assertEquals(50000L, tx.outputs[0].value);
    }

    @Test
    public void extractPrevOutpoint() throws CryptoError {
        byte[] raw = HexCodec.decode(TWO_IN_ONE_OUT_HEX);
        TxData tx = TxSerializer.parse(raw);

        // Input 0 prevhash: 32 bytes of 0xAA
        byte[] expectedHash0 = new byte[32];
        for (int i = 0; i < 32; i++) {
            expectedHash0[i] = (byte) 0xAA;
        }
        assertArrayEquals(expectedHash0, tx.inputs[0].prevTxHash);
        assertEquals(0, tx.inputs[0].prevIndex);

        // Input 1 prevhash: 32 bytes of 0xBB
        byte[] expectedHash1 = new byte[32];
        for (int i = 0; i < 32; i++) {
            expectedHash1[i] = (byte) 0xBB;
        }
        assertArrayEquals(expectedHash1, tx.inputs[1].prevTxHash);
        assertEquals(1, tx.inputs[1].prevIndex);
    }

    /** Bitcoin genesis block coinbase transaction. */
    private static final String GENESIS_COINBASE_HEX =
        "01000000010000000000000000000000000000000000000000000000000000000000000000"
        + "ffffffff4d04ffff001d0104455468652054696d65732030332f4a616e2f323030392043"
        + "68616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f7574"
        + "20666f722062616e6b73ffffffff0100f2052a01000000434104678afdb0fe5548271967"
        + "f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec1"
        + "12de5c384df7ba0b8d578a4c702b6bf11d5fac00000000";

    /** BIP143 native P2WPKH example, fully signed (witness serialization). */
    private static final String BIP143_SIGNED_TX_HEX =
        "01000000000102fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4"
        + "ad969f00000000494830450221008b9d1dc26ba6a9cb62127b02742fa9d754cd3bebf337"
        + "f7a55d114c8e5cdd30be022040529b194ba3f9281a99f2b1c0a19c0489bc22ede944ccf4"
        + "ecbab4cc618ef3ed01eeffffffef51e1b804cc89d182d279655c3aa89e815b1b309fe287"
        + "d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df3"
        + "78db99f66f85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4d"
        + "be6a21b2d50ce2f0167faa815988ac000247304402203609e17b84f6a7d30c80bfa610b5"
        + "b4542f32a8a0d5447a12fb1366d7f01cc44a0220573a954c4518331561406f90300e8f33"
        + "58f51928d43c212a8caed02de67eebee0121025476c2e83188368da1ff3e292e7acafcdb"
        + "3566bb0ad253f62fc70f07aeee635711000000";

    private static String displayTxid(byte[] internal) {
        byte[] r = new byte[internal.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = internal[internal.length - 1 - i];
        }
        return HexCodec.encode(r);
    }

    @Test
    public void computeTxidGenesisCoinbase() throws CryptoError {
        TxData tx = TxSerializer.parse(HexCodec.decode(GENESIS_COINBASE_HEX));
        assertEquals(1, tx.version);
        assertEquals(1, tx.inputs.length);
        assertEquals(0xffffffff, tx.inputs[0].prevIndex);
        assertEquals(5000000000L, tx.outputs[0].value);
        assertEquals("4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b",
            displayTxid(TxSerializer.computeTxid(tx)));
    }

    @Test
    public void parseAllowWitnessSkipsWitnessAndComputesTxid() throws CryptoError {
        byte[] raw = HexCodec.decode(BIP143_SIGNED_TX_HEX);
        TxData tx = TxSerializer.parseAllowWitness(raw);
        assertEquals(1, tx.version);
        assertEquals(2, tx.inputs.length);
        assertEquals(2, tx.outputs.length);
        assertEquals(0x11, tx.locktime);
        assertEquals(0xffffffeeL, tx.inputs[0].sequence);
        assertEquals(112340000L, tx.outputs[0].value);
        assertEquals(223450000L, tx.outputs[1].value);
        // txid never covers witness data
        assertEquals("e8151a2af31c368a35053ddd4bdb285a8595c769a3ad83e0fa02314a602d4609",
            displayTxid(TxSerializer.computeTxid(tx)));
    }

    @Test
    public void strictParseRejectsWitnessSerialization() throws CryptoError {
        byte[] raw = HexCodec.decode(BIP143_SIGNED_TX_HEX);
        try {
            TxSerializer.parse(raw);
            fail("Expected CryptoError: PSBT unsigned tx must not carry witnesses");
        } catch (CryptoError e) {
            // expected
        }
    }

    @Test
    public void rejectsHugeInputCountWithoutAllocating() throws CryptoError {
        // version + input count 0x7FFFFFFF + a few bytes
        byte[] raw = HexCodec.decode("02000000" + "feffffff7f" + "0000000000");
        try {
            TxSerializer.parse(raw);
            fail("Expected CryptoError");
        } catch (CryptoError e) {
            assertTrue(e.getMessage().indexOf("input count") >= 0);
        }
    }

    @Test
    public void rejectsTruncatedInput() throws CryptoError {
        // version + 1 input, then only 10 bytes
        byte[] raw = HexCodec.decode("02000000" + "01" + "00000000000000000000");
        try {
            TxSerializer.parse(raw);
            fail("Expected CryptoError");
        } catch (CryptoError e) {
            // expected
        }
    }

    @Test
    public void rejectsTrailingBytes() throws CryptoError {
        byte[] raw = HexCodec.decode(ONE_IN_ONE_OUT_HEX + "00");
        try {
            TxSerializer.parse(raw);
            fail("Expected CryptoError for trailing bytes");
        } catch (CryptoError e) {
            assertTrue(e.getMessage().indexOf("Trailing") >= 0);
        }
    }

    @Test
    public void outputValueAndScript() throws CryptoError {
        byte[] raw = HexCodec.decode(ONE_IN_ONE_OUT_HEX);
        TxData tx = TxSerializer.parse(raw);

        assertEquals(1000L, tx.outputs[0].value);

        // scriptPubKey = 0014 + 20 zero bytes (P2WPKH)
        byte[] expectedScript = new byte[22];
        expectedScript[0] = 0x00;
        expectedScript[1] = 0x14;
        // bytes 2-21 are zero
        assertArrayEquals(expectedScript, tx.outputs[0].scriptPubKey);
        assertEquals(22, tx.outputs[0].scriptPubKey.length);
    }
}
