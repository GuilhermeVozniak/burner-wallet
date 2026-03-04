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
