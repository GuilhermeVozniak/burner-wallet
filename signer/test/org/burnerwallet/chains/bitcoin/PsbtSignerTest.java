package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.burnerwallet.core.HashUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class PsbtSignerTest {
    private static final String ABANDON_MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    @Test
    public void signProducesValidPartialSig() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key signingKey = Bip32Derivation.derivePath(master, "m/84'/0'/0'/0/0");
        byte[] pubKey = signingKey.getPublicKeyBytes();
        byte[] pubKeyHash = HashUtils.hash160(pubKey);

        // Build unsigned tx
        String txHex =
            "0200000001" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "00000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" +
            "0014" + HexCodec.encode(pubKeyHash) + "00000000";

        PsbtTransaction psbtTx = new PsbtTransaction();
        psbtTx.unsignedTxBytes = HexCodec.decode(txHex);
        psbtTx.unsignedTx = TxSerializer.parse(psbtTx.unsignedTxBytes);
        psbtTx.inputs = new PsbtInput[1];
        psbtTx.inputs[0] = new PsbtInput();
        psbtTx.inputs[0].witnessUtxoValue = 50000L;
        psbtTx.inputs[0].witnessUtxoScript = HexCodec.decode("0014" + HexCodec.encode(pubKeyHash));
        psbtTx.outputs = new PsbtOutput[1];
        psbtTx.outputs[0] = new PsbtOutput();

        PsbtSigner.sign(psbtTx, seed, false);

        assertNotNull(psbtTx.inputs[0].partialSigValue);
        assertNotNull(psbtTx.inputs[0].partialSigKey);
        assertArrayEquals(pubKey, psbtTx.inputs[0].partialSigKey);
        assertTrue(psbtTx.inputs[0].partialSigValue.length > 0);
    }

    @Test
    public void signedPsbtSerializesAndReparses() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key signingKey = Bip32Derivation.derivePath(master, "m/84'/0'/0'/0/0");
        byte[] pubKey = signingKey.getPublicKeyBytes();
        byte[] pubKeyHash = HashUtils.hash160(pubKey);

        String txHex =
            "0200000001" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "00000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" +
            "0014" + HexCodec.encode(pubKeyHash) + "00000000";

        PsbtTransaction psbtTx = new PsbtTransaction();
        psbtTx.unsignedTxBytes = HexCodec.decode(txHex);
        psbtTx.unsignedTx = TxSerializer.parse(psbtTx.unsignedTxBytes);
        psbtTx.inputs = new PsbtInput[1];
        psbtTx.inputs[0] = new PsbtInput();
        psbtTx.inputs[0].witnessUtxoValue = 50000L;
        psbtTx.inputs[0].witnessUtxoScript = HexCodec.decode("0014" + HexCodec.encode(pubKeyHash));
        psbtTx.outputs = new PsbtOutput[1];
        psbtTx.outputs[0] = new PsbtOutput();

        PsbtSigner.sign(psbtTx, seed, false);

        byte[] serialized = PsbtSerializer.serialize(psbtTx);
        assertNotNull(serialized);
        assertEquals(0x70, serialized[0] & 0xFF);

        // Re-parse and verify signature preserved
        PsbtTransaction reparsed = PsbtParser.parse(serialized);
        assertNotNull(reparsed.inputs[0].partialSigValue);
        assertArrayEquals(psbtTx.inputs[0].partialSigKey, reparsed.inputs[0].partialSigKey);
    }

    @Test
    public void signTestnet() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key signingKey = Bip32Derivation.derivePath(master, "m/84'/1'/0'/0/0");
        byte[] pubKey = signingKey.getPublicKeyBytes();
        byte[] pubKeyHash = HashUtils.hash160(pubKey);

        String txHex =
            "0200000001" +
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
            "00000000" + "00" + "ffffffff" +
            "01" + "e803000000000000" + "16" +
            "0014" + HexCodec.encode(pubKeyHash) + "00000000";

        PsbtTransaction psbtTx = new PsbtTransaction();
        psbtTx.unsignedTxBytes = HexCodec.decode(txHex);
        psbtTx.unsignedTx = TxSerializer.parse(psbtTx.unsignedTxBytes);
        psbtTx.inputs = new PsbtInput[1];
        psbtTx.inputs[0] = new PsbtInput();
        psbtTx.inputs[0].witnessUtxoValue = 50000L;
        psbtTx.inputs[0].witnessUtxoScript = HexCodec.decode("0014" + HexCodec.encode(pubKeyHash));
        psbtTx.outputs = new PsbtOutput[1];
        psbtTx.outputs[0] = new PsbtOutput();

        PsbtSigner.sign(psbtTx, seed, true);

        assertNotNull(psbtTx.inputs[0].partialSigValue);
        assertArrayEquals(pubKey, psbtTx.inputs[0].partialSigKey);
    }
}
