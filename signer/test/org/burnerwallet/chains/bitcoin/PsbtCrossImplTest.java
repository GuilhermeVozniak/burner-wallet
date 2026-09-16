package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Cross-implementation PSBT signing test vectors.
 *
 * Uses the well-known "abandon...about" mnemonic to derive keys,
 * constructs a funding transaction and a P2WPKH spend of it, signs the
 * spend, and verifies deterministic results that both Java ME and Rust
 * implementations must agree on.
 *
 * The captured values (previous tx, unsigned tx, sighash, signature,
 * serialized PSBTs) are stored in protocol/vectors/psbt-signing.json.
 * The Rust companion recomputes the sighash, verifies the signature, and
 * finalizes signed_psbt from those bytes.
 */
public class PsbtCrossImplTest {

    private static final String ABANDON_MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    // Expected values from protocol/vectors/psbt-signing.json
    private static final String EXPECTED_PUBKEY =
        "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c";
    private static final String EXPECTED_PUBKEY_HASH =
        "c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2";
    private static final String EXPECTED_PREV_TX =
        "02000000010000000000000000000000000000000000000000000000000000000000000000"
        + "ffffffff025151ffffffff01400d030000000000160014c0cebcd6c3d3ca8c75dc5ec62ebe"
        + "55330ef910e200000000";
    /** Display (big-endian) txid of the funding transaction. */
    private static final String EXPECTED_PREV_TXID =
        "438fe1496d362e240509e01cc1e99469513528ba7a354d08a9e87be7e256b82b";
    private static final String EXPECTED_UNSIGNED_TX =
        "02000000012bb856e2e77be8a9084d357aba2835516994e9c11ce00905242e366d49e18f43"
        + "0000000000fdffffff01a086010000000000160014c0cebcd6c3d3ca8c75dc5ec62ebe5533"
        + "0ef910e200350c00";
    private static final String EXPECTED_SIGHASH =
        "48cb2f143f6434c54016034e0690b5a67bf6f47b5d7a58a922e9b8d5f8bd3988";
    private static final String EXPECTED_SIGNATURE =
        "304402205e8f445d690d903f2b1549ba42ece51c21cd26b56be7f64ed8c3cc975cc492fc"
        + "02206bd9e21297025c0f5490d8d85594db170a90e1c9bbc8c9aedea8c349e73ea6f901";
    private static final String EXPECTED_UNSIGNED_PSBT =
        "70736274ff0100520200000001"
        + "2bb856e2e77be8a9084d357aba2835516994e9c11ce00905242e366d49e18f43"
        + "0000000000fdffffff01a086010000000000160014c0cebcd6c3d3ca8c75dc5ec62ebe5533"
        + "0ef910e200350c0000"
        + "010054" + EXPECTED_PREV_TX
        + "01011f400d030000000000160014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
        + "0000";
    private static final String EXPECTED_SIGNED_PSBT =
        "70736274ff0100520200000001"
        + "2bb856e2e77be8a9084d357aba2835516994e9c11ce00905242e366d49e18f43"
        + "0000000000fdffffff01a086010000000000160014c0cebcd6c3d3ca8c75dc5ec62ebe5533"
        + "0ef910e200350c0000"
        + "010054" + EXPECTED_PREV_TX
        + "01011f400d030000000000160014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
        + "2202" + EXPECTED_PUBKEY + "47" + EXPECTED_SIGNATURE
        + "0000";

    private static final long FUNDING_SATS = 200000L;
    private static final long SEND_SATS = 100000L;
    private static final long RBF_SEQUENCE = 0xFFFFFFFDL;
    private static final int LOCKTIME = 800000;

    /** Reverse a 32-byte internal-order hash into display order hex. */
    private static String displayHex(byte[] internal) {
        byte[] r = new byte[internal.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = internal[internal.length - 1 - i];
        }
        return HexCodec.encode(r);
    }

    /** Funding transaction from the vector: coinbase-like, pays our script. */
    private static TxData fundingTx(byte[] script) {
        TxData prev = new TxData();
        prev.version = 2;
        TxInput cin = new TxInput();
        cin.prevTxHash = new byte[32];
        cin.prevIndex = 0xffffffff;
        cin.scriptSig = new byte[] { 0x51, 0x51 };
        cin.sequence = 0xFFFFFFFFL;
        prev.inputs = new TxInput[] { cin };
        TxOutput out = new TxOutput();
        out.value = FUNDING_SATS;
        out.scriptPubKey = script;
        prev.outputs = new TxOutput[] { out };
        prev.locktime = 0;
        return prev;
    }

    /** Spending transaction from the vector. */
    private static TxData spendTx(byte[] prevTxid, byte[] script) {
        TxData tx = new TxData();
        tx.version = 2;
        TxInput input = new TxInput();
        input.prevTxHash = prevTxid;
        input.prevIndex = 0;
        input.scriptSig = new byte[0];
        input.sequence = RBF_SEQUENCE;
        tx.inputs = new TxInput[] { input };
        TxOutput output = new TxOutput();
        output.value = SEND_SATS;
        output.scriptPubKey = script;
        tx.outputs = new TxOutput[] { output };
        tx.locktime = LOCKTIME;
        return tx;
    }

    private static PsbtTransaction buildPsbt(byte[] script) throws CryptoError {
        TxData prev = fundingTx(script);
        TxData tx = spendTx(TxSerializer.computeTxid(prev), script);

        PsbtTransaction psbt = new PsbtTransaction();
        psbt.unsignedTxBytes = TxSerializer.serialize(tx);
        psbt.unsignedTx = tx;
        psbt.inputs = new PsbtInput[1];
        psbt.inputs[0] = new PsbtInput();
        psbt.inputs[0].nonWitnessUtxo = TxSerializer.serialize(prev);
        psbt.inputs[0].witnessUtxoValue = FUNDING_SATS;
        psbt.inputs[0].witnessUtxoScript = script;
        psbt.outputs = new PsbtOutput[1];
        psbt.outputs[0] = new PsbtOutput();
        return psbt;
    }

    @Test
    public void crossImplPsbtSignature() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");

        // Derive key at m/84'/0'/0'/0/0
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key key = Bip32Derivation.derivePath(master, "m/84'/0'/0'/0/0");
        byte[] pubKey = key.getPublicKeyBytes();
        byte[] pubKeyHash = HashUtils.hash160(pubKey);

        assertEquals("pubkey must match vector",
            EXPECTED_PUBKEY, HexCodec.encode(pubKey));
        assertEquals("pubKeyHash must match vector",
            EXPECTED_PUBKEY_HASH, HexCodec.encode(pubKeyHash));

        byte[] script = HexCodec.decode("0014" + EXPECTED_PUBKEY_HASH);

        // Funding transaction and its txid
        TxData prev = fundingTx(script);
        assertEquals("previous tx must match vector",
            EXPECTED_PREV_TX, HexCodec.encode(TxSerializer.serialize(prev)));
        assertEquals("previous txid must match vector",
            EXPECTED_PREV_TXID, displayHex(TxSerializer.computeTxid(prev)));

        // Unsigned spend
        PsbtTransaction psbt = buildPsbt(script);
        assertEquals("unsigned tx must match vector",
            EXPECTED_UNSIGNED_TX, HexCodec.encode(psbt.unsignedTxBytes));

        // Sighash
        byte[] scriptCode = Bip143Sighash.p2wpkhScriptCode(pubKeyHash);
        byte[] sighash = Bip143Sighash.computeSighash(
            psbt.unsignedTx, 0, scriptCode, FUNDING_SATS, Bip143Sighash.SIGHASH_ALL);
        assertEquals("sighash must match vector",
            EXPECTED_SIGHASH, HexCodec.encode(sighash));

        // Unsigned PSBT bytes (before signing)
        assertEquals("unsigned PSBT must match vector",
            EXPECTED_UNSIGNED_PSBT, HexCodec.encode(PsbtSerializer.serialize(psbt)));

        // Sign
        int signed = PsbtSigner.sign(psbt, seed, false);
        assertEquals(1, signed);

        assertNotNull("partialSigValue must not be null", psbt.inputs[0].partialSigValue);
        assertEquals("signature must match vector",
            EXPECTED_SIGNATURE, HexCodec.encode(psbt.inputs[0].partialSigValue));
        assertArrayEquals("public key must match", pubKey, psbt.inputs[0].partialSigKey);

        // Signed PSBT bytes
        byte[] signedPsbt = PsbtSerializer.serialize(psbt);
        assertEquals("signed PSBT must match vector",
            EXPECTED_SIGNED_PSBT, HexCodec.encode(signedPsbt));

        // Re-parse and verify round-trip
        PsbtTransaction reparsed = PsbtParser.parse(signedPsbt);
        assertNotNull(reparsed.inputs[0].partialSigValue);
        assertArrayEquals(psbt.inputs[0].partialSigValue, reparsed.inputs[0].partialSigValue);
        assertArrayEquals(psbt.inputs[0].partialSigKey, reparsed.inputs[0].partialSigKey);
        assertArrayEquals(psbt.inputs[0].nonWitnessUtxo, reparsed.inputs[0].nonWitnessUtxo);
        assertArrayEquals(signedPsbt, PsbtSerializer.serialize(reparsed));
    }

    @Test
    public void vectorPsbtParsesAndSignsFromBytes() throws CryptoError {
        // Start from the vector's unsigned_psbt bytes (as the companion emits)
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        PsbtTransaction psbt = PsbtParser.parse(HexCodec.decode(EXPECTED_UNSIGNED_PSBT));
        PsbtSigner.verifyInputs(psbt);
        assertEquals(FUNDING_SATS - SEND_SATS, psbt.getFee());
        assertEquals(1, PsbtSigner.sign(psbt, seed, false));
        assertEquals(EXPECTED_SIGNED_PSBT, HexCodec.encode(PsbtSerializer.serialize(psbt)));
    }

    @Test
    public void signatureIsDeterministic() throws CryptoError {
        byte[] seed = Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
        byte[] script = HexCodec.decode("0014" + EXPECTED_PUBKEY_HASH);

        PsbtTransaction psbt1 = buildPsbt(script);
        PsbtSigner.sign(psbt1, seed, false);

        PsbtTransaction psbt2 = buildPsbt(script);
        PsbtSigner.sign(psbt2, seed, false);

        // Signatures must be identical (RFC 6979 deterministic)
        assertNotNull(psbt1.inputs[0].partialSigValue);
        assertArrayEquals("deterministic signatures must match",
            psbt1.inputs[0].partialSigValue, psbt2.inputs[0].partialSigValue);
    }

    @Test
    public void psbtSerializeRoundTrip() throws CryptoError {
        byte[] script = HexCodec.decode("0014" + EXPECTED_PUBKEY_HASH);
        PsbtTransaction psbt = buildPsbt(script);

        byte[] serialized = PsbtSerializer.serialize(psbt);
        PsbtTransaction reparsed = PsbtParser.parse(serialized);

        assertNotNull(reparsed.unsignedTx);
        assertEquals(1, reparsed.unsignedTx.inputs.length);
        assertEquals(1, reparsed.unsignedTx.outputs.length);
        assertEquals(RBF_SEQUENCE, reparsed.unsignedTx.inputs[0].sequence);
        assertEquals(LOCKTIME, reparsed.unsignedTx.locktime);
        assertEquals(FUNDING_SATS, reparsed.inputs[0].witnessUtxoValue);
        assertArrayEquals(psbt.inputs[0].witnessUtxoScript,
            reparsed.inputs[0].witnessUtxoScript);
        assertArrayEquals(psbt.inputs[0].nonWitnessUtxo,
            reparsed.inputs[0].nonWitnessUtxo);

        byte[] reserialized = PsbtSerializer.serialize(reparsed);
        assertArrayEquals("double round-trip must produce identical bytes",
            serialized, reserialized);
    }
}
