package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for Bip143Sighash (BIP143 segwit signature hash).
 */
public class Bip143SighashTest {

    /**
     * BIP143 native P2WPKH test vector (from the specification).
     *
     * Transaction: 2-in, 2-out, version 1, locktime 17.
     * Signing input index 1 with SIGHASH_ALL.
     * Witness UTXO value: 6.0 BTC (600000000 sats).
     * PubKeyHash: 1d0f172a0ecb48aee1be1f2687d2963ae33f71a1
     * Expected sighash: c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670
     */
    private static final String BIP143_TX_HEX =
        "0100000002"                                                 // version 1, 2 inputs
        + "fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f"
        + "00000000"                                                 // input 0: prevIndex 0
        + "00"                                                       // empty scriptSig
        + "eeffffff"                                                 // sequence 0xffffffee
        + "ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a"
        + "01000000"                                                 // input 1: prevIndex 1
        + "00"                                                       // empty scriptSig
        + "ffffffff"                                                 // sequence 0xffffffff
        + "02"                                                       // 2 outputs
        + "202cb20600000000"                                         // output 0: 112340000 sats
        + "19"                                                       // scriptPubKey len 25
        + "76a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac"       // P2PKH
        + "9093510d00000000"                                         // output 1: 223450000 sats
        + "19"                                                       // scriptPubKey len 25
        + "76a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac"       // P2PKH
        + "11000000";                                                // locktime 17

    @Test
    public void nativeP2wpkhSighashAll() throws CryptoError {
        byte[] raw = HexCodec.decode(BIP143_TX_HEX);
        TxData tx = TxSerializer.parse(raw);

        // PubKeyHash for the signing input (index 1)
        byte[] pubKeyHash = HexCodec.decode("1d0f172a0ecb48aee1be1f2687d2963ae33f71a1");
        byte[] scriptCode = Bip143Sighash.p2wpkhScriptCode(pubKeyHash);

        // Witness UTXO value: 600000000 sats (6.0 BTC)
        long value = 600000000L;

        byte[] sighash = Bip143Sighash.computeSighash(
            tx, 1, scriptCode, value, Bip143Sighash.SIGHASH_ALL);

        String expected = "c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670";
        assertEquals(expected, HexCodec.encode(sighash));
    }

    @Test
    public void scriptCodeFromPubKeyHash() throws CryptoError {
        byte[] pubKeyHash = HexCodec.decode("1d0f172a0ecb48aee1be1f2687d2963ae33f71a1");
        byte[] scriptCode = Bip143Sighash.p2wpkhScriptCode(pubKeyHash);

        // P2WPKH scriptCode = OP_DUP OP_HASH160 OP_PUSH20 <hash20> OP_EQUALVERIFY OP_CHECKSIG
        assertEquals(25, scriptCode.length);

        // OP_DUP = 0x76
        assertEquals((byte) 0x76, scriptCode[0]);
        // OP_HASH160 = 0xa9
        assertEquals((byte) 0xa9, scriptCode[1]);
        // OP_PUSH20 = 0x14
        assertEquals((byte) 0x14, scriptCode[2]);

        // 20-byte pubkey hash at positions 3..22
        for (int i = 0; i < 20; i++) {
            assertEquals(pubKeyHash[i], scriptCode[3 + i]);
        }

        // OP_EQUALVERIFY = 0x88
        assertEquals((byte) 0x88, scriptCode[23]);
        // OP_CHECKSIG = 0xac
        assertEquals((byte) 0xac, scriptCode[24]);
    }

    @Test
    public void sighashTypeByte() {
        assertEquals(0x01, Bip143Sighash.SIGHASH_ALL);
        assertEquals(0x02, Bip143Sighash.SIGHASH_NONE);
        assertEquals(0x03, Bip143Sighash.SIGHASH_SINGLE);
        assertEquals(0x80, Bip143Sighash.SIGHASH_ANYONECANPAY);
    }

    @Test
    public void differentSighashProducesDifferentHash() throws CryptoError {
        byte[] raw = HexCodec.decode(BIP143_TX_HEX);
        TxData tx = TxSerializer.parse(raw);

        byte[] pubKeyHash = HexCodec.decode("1d0f172a0ecb48aee1be1f2687d2963ae33f71a1");
        byte[] scriptCode = Bip143Sighash.p2wpkhScriptCode(pubKeyHash);
        long value = 600000000L;

        byte[] sighashAll = Bip143Sighash.computeSighash(
            tx, 1, scriptCode, value, Bip143Sighash.SIGHASH_ALL);
        byte[] sighashNone = Bip143Sighash.computeSighash(
            tx, 1, scriptCode, value, Bip143Sighash.SIGHASH_NONE);

        // SIGHASH_ALL and SIGHASH_NONE must produce different digests
        assertFalse(
            "SIGHASH_ALL and SIGHASH_NONE must differ",
            HexCodec.encode(sighashAll).equals(HexCodec.encode(sighashNone)));
    }
}
