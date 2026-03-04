package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Cross-implementation PSBT signing test vectors.
 *
 * Uses the well-known "abandon...about" mnemonic to derive keys,
 * constructs a simple P2WPKH transaction, signs it, and verifies
 * deterministic results that both Java ME and Rust implementations
 * must agree on.
 *
 * The captured intermediate values (sighash, signature, serialized
 * PSBT) are stored in protocol/vectors/psbt-signing.json for
 * cross-implementation verification.
 */
public class PsbtCrossImplTest {

    private static final String ABANDON_MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    // Expected values from protocol/vectors/psbt-signing.json
    private static final String EXPECTED_PUBKEY =
        "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c";
    private static final String EXPECTED_PUBKEY_HASH =
        "c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2";
    private static final String EXPECTED_SIGHASH =
        "60750e6c99870ec5f3666575e81cc4798a82058955757a3d8377ac017f360fba";
    private static final String EXPECTED_SIGNATURE =
        "30440220036fc40e1b9e9abdcdc42215b5cb279b61d85cb34b89e1cc16948b00a2285ceb"
        + "02202b6c1261ee8cf198c4a380753d98e12f25bb94b9e576574bb19e96f03cfbea9101";
    private static final String EXPECTED_UNSIGNED_PSBT =
        "70736274ff01005202000000"
        + "01aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        + "0000000000ffffffff01a086010000000000160014c0cebcd6c3d3ca8c75dc5ec6"
        + "2ebe55330ef910e2000000000001011f400d030000000000160014c0cebcd6c3d3"
        + "ca8c75dc5ec62ebe55330ef910e20000";
    private static final String EXPECTED_SIGNED_PSBT =
        "70736274ff01005202000000"
        + "01aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        + "0000000000ffffffff01a086010000000000160014c0cebcd6c3d3ca8c75dc5ec6"
        + "2ebe55330ef910e2000000000001011f400d030000000000160014c0cebcd6c3d3"
        + "ca8c75dc5ec62ebe55330ef910e2"
        + "22020330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91"
        + "af3c4730440220036fc40e1b9e9abdcdc42215b5cb279b61d85cb34b89e1cc1694"
        + "8b00a2285ceb02202b6c1261ee8cf198c4a380753d98e12f25bb94b9e576574bb1"
        + "9e96f03cfbea91010000";

    @Test
    public void crossImplPsbtSignature() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");

        // Derive key at m/84'/0'/0'/0/0
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key key = Bip32Derivation.derivePath(master, "m/84'/0'/0'/0/0");
        byte[] pubKey = key.getPublicKeyBytes();
        byte[] pubKeyHash = HashUtils.hash160(pubKey);

        // Verify against known vector values
        assertEquals("pubkey must match vector",
            EXPECTED_PUBKEY, HexCodec.encode(pubKey));
        assertEquals("pubKeyHash must match vector",
            EXPECTED_PUBKEY_HASH, HexCodec.encode(pubKeyHash));

        // Construct unsigned tx:
        // - version 2
        // - 1 input: spending fake prevout aaaa...aaaa:0 with empty scriptSig
        // - 1 output: 100000 sats to same address (P2WPKH)
        // - locktime 0
        byte[] prevTxHash = HexCodec.decode(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        TxData txData = new TxData();
        txData.version = 2;

        TxInput input = new TxInput();
        input.prevTxHash = prevTxHash;
        input.prevIndex = 0;
        input.scriptSig = new byte[0];
        input.sequence = 0xFFFFFFFFL;
        txData.inputs = new TxInput[]{input};

        TxOutput output = new TxOutput();
        output.value = 100000L;
        output.scriptPubKey = HexCodec.decode("0014" + EXPECTED_PUBKEY_HASH);
        txData.outputs = new TxOutput[]{output};
        txData.locktime = 0;

        byte[] txBytes = TxSerializer.serialize(txData);

        // Build PSBT
        PsbtTransaction psbt = new PsbtTransaction();
        psbt.unsignedTxBytes = txBytes;
        psbt.unsignedTx = txData;
        psbt.inputs = new PsbtInput[1];
        psbt.inputs[0] = new PsbtInput();
        psbt.inputs[0].witnessUtxoValue = 200000L;
        psbt.inputs[0].witnessUtxoScript = HexCodec.decode("0014" + EXPECTED_PUBKEY_HASH);
        psbt.outputs = new PsbtOutput[1];
        psbt.outputs[0] = new PsbtOutput();

        // Compute sighash manually for verification
        byte[] scriptCode = Bip143Sighash.p2wpkhScriptCode(pubKeyHash);
        byte[] sighash = Bip143Sighash.computeSighash(
            psbt.unsignedTx, 0, scriptCode, 200000L, Bip143Sighash.SIGHASH_ALL);

        // Verify sighash matches expected vector
        assertEquals("sighash must match vector",
            EXPECTED_SIGHASH, HexCodec.encode(sighash));

        // Sign
        PsbtSigner.sign(psbt, seed, false);

        // Verify signature matches expected vector
        assertNotNull("partialSigValue must not be null", psbt.inputs[0].partialSigValue);
        assertEquals("signature must match vector",
            EXPECTED_SIGNATURE, HexCodec.encode(psbt.inputs[0].partialSigValue));
        assertArrayEquals("public key must match", pubKey, psbt.inputs[0].partialSigKey);

        // Verify unsigned PSBT matches expected vector
        PsbtTransaction unsignedPsbt = buildUnsignedCopy(psbt);
        byte[] unsignedPsbtBytes = PsbtSerializer.serialize(unsignedPsbt);
        assertEquals("unsigned PSBT must match vector",
            EXPECTED_UNSIGNED_PSBT, HexCodec.encode(unsignedPsbtBytes));

        // Verify signed PSBT matches expected vector
        byte[] signedPsbt = PsbtSerializer.serialize(psbt);
        assertEquals("signed PSBT must match vector",
            EXPECTED_SIGNED_PSBT, HexCodec.encode(signedPsbt));

        // Re-parse and verify round-trip
        PsbtTransaction reparsed = PsbtParser.parse(signedPsbt);
        assertNotNull("reparsed partialSigValue must not be null",
            reparsed.inputs[0].partialSigValue);
        assertArrayEquals("round-trip signature must match",
            psbt.inputs[0].partialSigValue, reparsed.inputs[0].partialSigValue);
        assertArrayEquals("round-trip pubkey must match",
            psbt.inputs[0].partialSigKey, reparsed.inputs[0].partialSigKey);
    }

    @Test
    public void signatureIsDeterministic() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key key = Bip32Derivation.derivePath(master, "m/84'/0'/0'/0/0");
        byte[] pubKey = key.getPublicKeyBytes();
        byte[] pubKeyHash = HashUtils.hash160(pubKey);

        byte[] prevTxHash = HexCodec.decode(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        // Build tx
        TxData txData = new TxData();
        txData.version = 2;
        TxInput input = new TxInput();
        input.prevTxHash = prevTxHash;
        input.prevIndex = 0;
        input.scriptSig = new byte[0];
        input.sequence = 0xFFFFFFFFL;
        txData.inputs = new TxInput[]{input};

        TxOutput output = new TxOutput();
        output.value = 100000L;
        output.scriptPubKey = HexCodec.decode("0014" + HexCodec.encode(pubKeyHash));
        txData.outputs = new TxOutput[]{output};
        txData.locktime = 0;

        byte[] txBytes = TxSerializer.serialize(txData);

        // Sign twice
        PsbtTransaction psbt1 = buildPsbt(txBytes, txData, pubKeyHash);
        PsbtSigner.sign(psbt1, seed, false);

        // Rebuild txData for second sign (since it's the same data)
        TxData txData2 = TxSerializer.parse(txBytes);
        PsbtTransaction psbt2 = buildPsbt(txBytes, txData2, pubKeyHash);
        PsbtSigner.sign(psbt2, seed, false);

        // Signatures must be identical (RFC 6979 deterministic)
        assertNotNull("first signature must exist", psbt1.inputs[0].partialSigValue);
        assertNotNull("second signature must exist", psbt2.inputs[0].partialSigValue);
        assertArrayEquals("deterministic signatures must match",
            psbt1.inputs[0].partialSigValue, psbt2.inputs[0].partialSigValue);
    }

    @Test
    public void psbtSerializeRoundTrip() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key key = Bip32Derivation.derivePath(master, "m/84'/0'/0'/0/0");
        byte[] pubKeyHash = HashUtils.hash160(key.getPublicKeyBytes());

        byte[] prevTxHash = HexCodec.decode(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        TxData txData = new TxData();
        txData.version = 2;
        TxInput input = new TxInput();
        input.prevTxHash = prevTxHash;
        input.prevIndex = 0;
        input.scriptSig = new byte[0];
        input.sequence = 0xFFFFFFFFL;
        txData.inputs = new TxInput[]{input};

        TxOutput output = new TxOutput();
        output.value = 100000L;
        output.scriptPubKey = HexCodec.decode("0014" + HexCodec.encode(pubKeyHash));
        txData.outputs = new TxOutput[]{output};
        txData.locktime = 0;

        byte[] txBytes = TxSerializer.serialize(txData);

        // Build unsigned PSBT
        PsbtTransaction psbt = buildPsbt(txBytes, txData, pubKeyHash);

        // Serialize and re-parse
        byte[] serialized = PsbtSerializer.serialize(psbt);
        PsbtTransaction reparsed = PsbtParser.parse(serialized);

        // Verify structure
        assertNotNull(reparsed.unsignedTx);
        assertEquals(1, reparsed.unsignedTx.inputs.length);
        assertEquals(1, reparsed.unsignedTx.outputs.length);
        assertEquals(200000L, reparsed.inputs[0].witnessUtxoValue);
        assertArrayEquals(psbt.inputs[0].witnessUtxoScript,
            reparsed.inputs[0].witnessUtxoScript);

        // Serialize again and verify bytes match
        byte[] reserialized = PsbtSerializer.serialize(reparsed);
        assertArrayEquals("double round-trip must produce identical bytes",
            serialized, reserialized);
    }

    private PsbtTransaction buildPsbt(byte[] txBytes, TxData txData,
                                       byte[] pubKeyHash) throws CryptoError {
        PsbtTransaction psbt = new PsbtTransaction();
        psbt.unsignedTxBytes = txBytes;
        psbt.unsignedTx = txData;
        psbt.inputs = new PsbtInput[1];
        psbt.inputs[0] = new PsbtInput();
        psbt.inputs[0].witnessUtxoValue = 200000L;
        psbt.inputs[0].witnessUtxoScript = HexCodec.decode(
            "0014" + HexCodec.encode(pubKeyHash));
        psbt.outputs = new PsbtOutput[1];
        psbt.outputs[0] = new PsbtOutput();
        return psbt;
    }

    private PsbtTransaction buildUnsignedCopy(PsbtTransaction signed) {
        PsbtTransaction unsigned = new PsbtTransaction();
        unsigned.unsignedTxBytes = signed.unsignedTxBytes;
        unsigned.unsignedTx = signed.unsignedTx;
        unsigned.inputs = new PsbtInput[signed.inputs.length];
        for (int i = 0; i < signed.inputs.length; i++) {
            unsigned.inputs[i] = new PsbtInput();
            unsigned.inputs[i].witnessUtxoValue = signed.inputs[i].witnessUtxoValue;
            unsigned.inputs[i].witnessUtxoScript = signed.inputs[i].witnessUtxoScript;
        }
        unsigned.outputs = new PsbtOutput[signed.outputs.length];
        for (int i = 0; i < signed.outputs.length; i++) {
            unsigned.outputs[i] = new PsbtOutput();
        }
        return unsigned;
    }
}
