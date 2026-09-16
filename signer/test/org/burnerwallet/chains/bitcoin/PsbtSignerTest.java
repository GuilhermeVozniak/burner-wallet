package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.burnerwallet.core.HashUtils;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for PsbtSigner: signing policy (SIGHASH_ALL only,
 * verified previous transactions, owned inputs only) and signature output.
 */
public class PsbtSignerTest {
    private static final String ABANDON_MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    private static final long FUNDING_SATS = 50000L;

    /** Build a coinbase-like funding transaction paying FUNDING_SATS to script. */
    private static TxData fundingTx(byte[] script, long value) {
        TxData prev = new TxData();
        prev.version = 2;
        TxInput cin = new TxInput();
        cin.prevTxHash = new byte[32];
        cin.prevIndex = 0xffffffff;
        cin.scriptSig = new byte[] { 0x51, 0x51 };
        cin.sequence = 0xffffffffL;
        prev.inputs = new TxInput[] { cin };
        TxOutput out = new TxOutput();
        out.value = value;
        out.scriptPubKey = script;
        prev.outputs = new TxOutput[] { out };
        prev.locktime = 0;
        return prev;
    }

    /** P2WPKH script for a pubkey hash. */
    private static byte[] p2wpkh(byte[] pkh) throws CryptoError {
        return HexCodec.decode("0014" + HexCodec.encode(pkh));
    }

    /**
     * Build a 1-in-1-out PSBT spending a funding tx that pays to
     * {@code inputScript}, with matching witness_utxo + non_witness_utxo.
     */
    private static PsbtTransaction buildPsbt(byte[] inputScript, byte[] outputScript)
            throws CryptoError {
        TxData prev = fundingTx(inputScript, FUNDING_SATS);
        byte[] prevBytes = TxSerializer.serialize(prev);

        TxData tx = new TxData();
        tx.version = 2;
        TxInput in = new TxInput();
        in.prevTxHash = TxSerializer.computeTxid(prev);
        in.prevIndex = 0;
        in.scriptSig = new byte[0];
        in.sequence = 0xfffffffdL;
        tx.inputs = new TxInput[] { in };
        TxOutput out = new TxOutput();
        out.value = 1000L;
        out.scriptPubKey = outputScript;
        tx.outputs = new TxOutput[] { out };
        tx.locktime = 0;

        PsbtTransaction psbt = new PsbtTransaction();
        psbt.unsignedTxBytes = TxSerializer.serialize(tx);
        psbt.unsignedTx = tx;
        psbt.inputs = new PsbtInput[] { new PsbtInput() };
        psbt.inputs[0].nonWitnessUtxo = prevBytes;
        psbt.inputs[0].witnessUtxoValue = FUNDING_SATS;
        psbt.inputs[0].witnessUtxoScript = inputScript;
        psbt.outputs = new PsbtOutput[] { new PsbtOutput() };
        return psbt;
    }

    private static byte[] seed() {
        return Bip39Mnemonic.toSeed(ABANDON_MNEMONIC, "");
    }

    private static byte[] pubKeyHashAt(byte[] seed, String path) throws CryptoError {
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key key = Bip32Derivation.derivePath(master, path);
        return HashUtils.hash160(key.getPublicKeyBytes());
    }

    @Test
    public void signProducesValidPartialSig() throws CryptoError {
        byte[] seed = seed();
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key signingKey = Bip32Derivation.derivePath(master, "m/84'/0'/0'/0/0");
        byte[] pubKey = signingKey.getPublicKeyBytes();
        byte[] script = p2wpkh(HashUtils.hash160(pubKey));

        PsbtTransaction psbt = buildPsbt(script, script);
        int signed = PsbtSigner.sign(psbt, seed, false);

        assertEquals(1, signed);
        assertNotNull(psbt.inputs[0].partialSigValue);
        assertArrayEquals(pubKey, psbt.inputs[0].partialSigKey);
        // DER signature + sighash byte; sighash byte must be SIGHASH_ALL
        byte[] sig = psbt.inputs[0].partialSigValue;
        assertEquals(0x30, sig[0] & 0xFF);
        assertEquals(Bip143Sighash.SIGHASH_ALL, sig[sig.length - 1] & 0xFF);
    }

    @Test
    public void signedPsbtSerializesAndReparses() throws CryptoError {
        byte[] seed = seed();
        byte[] script = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/0"));
        PsbtTransaction psbt = buildPsbt(script, script);
        PsbtSigner.sign(psbt, seed, false);

        byte[] serialized = PsbtSerializer.serialize(psbt);
        assertEquals(0x70, serialized[0] & 0xFF);

        PsbtTransaction reparsed = PsbtParser.parse(serialized);
        assertArrayEquals(psbt.inputs[0].partialSigKey, reparsed.inputs[0].partialSigKey);
        assertArrayEquals(psbt.inputs[0].partialSigValue, reparsed.inputs[0].partialSigValue);
        assertArrayEquals(psbt.inputs[0].nonWitnessUtxo, reparsed.inputs[0].nonWitnessUtxo);
        // The reparsed PSBT still satisfies the policy
        PsbtSigner.verifyInputs(reparsed);
    }

    @Test
    public void signTestnet() throws CryptoError {
        byte[] seed = seed();
        Bip32Key master = Bip32Derivation.masterFromSeed(seed);
        Bip32Key key = Bip32Derivation.derivePath(master, "m/84'/1'/0'/0/0");
        byte[] pubKey = key.getPublicKeyBytes();
        byte[] script = p2wpkh(HashUtils.hash160(pubKey));

        PsbtTransaction psbt = buildPsbt(script, script);
        assertEquals(1, PsbtSigner.sign(psbt, seed, true));
        assertArrayEquals(pubKey, psbt.inputs[0].partialSigKey);
    }

    @Test
    public void signsChangeChainInput() throws CryptoError {
        byte[] seed = seed();
        byte[] script = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/1/3"));
        PsbtTransaction psbt = buildPsbt(script, script);
        assertEquals(1, PsbtSigner.sign(psbt, seed, false));
    }

    @Test
    public void rejectsNonSighashAll() throws CryptoError {
        byte[] seed = seed();
        byte[] script = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/0"));
        PsbtTransaction psbt = buildPsbt(script, script);
        psbt.inputs[0].sighashType = Bip143Sighash.SIGHASH_NONE
            | Bip143Sighash.SIGHASH_ANYONECANPAY;
        try {
            PsbtSigner.sign(psbt, seed, false);
            fail("SIGHASH_NONE|ANYONECANPAY must be refused");
        } catch (CryptoError e) {
            assertTrue(e.getMessage(), e.getMessage().indexOf("SIGHASH_ALL") >= 0);
        }
        assertNull("no signature must be produced", psbt.inputs[0].partialSigValue);
    }

    @Test
    public void rejectsMissingNonWitnessUtxo() throws CryptoError {
        byte[] seed = seed();
        byte[] script = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/0"));
        PsbtTransaction psbt = buildPsbt(script, script);
        psbt.inputs[0].nonWitnessUtxo = null;
        try {
            PsbtSigner.sign(psbt, seed, false);
            fail("missing non_witness_utxo must be refused");
        } catch (CryptoError e) {
            assertTrue(e.getMessage(), e.getMessage().indexOf("non_witness_utxo") >= 0);
        }
    }

    @Test
    public void rejectsMissingWitnessUtxo() throws CryptoError {
        byte[] seed = seed();
        byte[] script = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/0"));
        PsbtTransaction psbt = buildPsbt(script, script);
        psbt.inputs[0].witnessUtxoScript = null;
        psbt.inputs[0].witnessUtxoValue = -1;
        try {
            PsbtSigner.sign(psbt, seed, false);
            fail("missing witness_utxo must be refused");
        } catch (CryptoError e) {
            assertTrue(e.getMessage(), e.getMessage().indexOf("witness_utxo") >= 0);
        }
    }

    @Test
    public void rejectsAmountMismatch() throws CryptoError {
        // The multi-round fee attack: companion claims a different amount
        // than the previous transaction actually pays.
        byte[] seed = seed();
        byte[] script = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/0"));
        PsbtTransaction psbt = buildPsbt(script, script);
        psbt.inputs[0].witnessUtxoValue = FUNDING_SATS - 1;
        try {
            PsbtSigner.sign(psbt, seed, false);
            fail("amount mismatch must be refused");
        } catch (CryptoError e) {
            assertTrue(e.getMessage(), e.getMessage().indexOf("amount") >= 0);
        }
    }

    @Test
    public void rejectsScriptMismatch() throws CryptoError {
        byte[] seed = seed();
        byte[] ours = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/0"));
        byte[] other = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/1"));
        // Funding tx pays to `other`, witness_utxo claims `ours`
        PsbtTransaction psbt = buildPsbt(other, ours);
        psbt.inputs[0].witnessUtxoScript = ours;
        try {
            PsbtSigner.sign(psbt, seed, false);
            fail("script mismatch must be refused");
        } catch (CryptoError e) {
            assertTrue(e.getMessage(), e.getMessage().indexOf("script") >= 0);
        }
    }

    @Test
    public void rejectsPrevTxidMismatch() throws CryptoError {
        byte[] seed = seed();
        byte[] script = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/0"));
        PsbtTransaction psbt = buildPsbt(script, script);
        psbt.unsignedTx.inputs[0].prevTxHash = HexCodec.decode(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        try {
            PsbtSigner.sign(psbt, seed, false);
            fail("outpoint/prev tx mismatch must be refused");
        } catch (CryptoError e) {
            assertTrue(e.getMessage(), e.getMessage().indexOf("outpoint") >= 0);
        }
    }

    @Test
    public void rejectsPrevIndexOutOfRange() throws CryptoError {
        byte[] seed = seed();
        byte[] script = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/0"));
        PsbtTransaction psbt = buildPsbt(script, script);
        psbt.unsignedTx.inputs[0].prevIndex = 5;
        try {
            PsbtSigner.sign(psbt, seed, false);
            fail("prev index out of range must be refused");
        } catch (CryptoError e) {
            assertTrue(e.getMessage(), e.getMessage().indexOf("out of range") >= 0);
        }
    }

    @Test
    public void rejectsForeignInput() throws CryptoError {
        byte[] seed = seed();
        // Receive index 25 is outside the signer's search window
        byte[] foreign = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/25"));
        PsbtTransaction psbt = buildPsbt(foreign, foreign);
        try {
            PsbtSigner.sign(psbt, seed, false);
            fail("input not controlled by the wallet must be refused");
        } catch (CryptoError e) {
            assertTrue(e.getMessage(), e.getMessage().indexOf("not controlled") >= 0);
        }
        assertNull(psbt.inputs[0].partialSigValue);
    }

    @Test
    public void rejectsNonP2wpkhInput() throws CryptoError {
        byte[] seed = seed();
        byte[] ours = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/0"));
        // P2WSH-shaped script (0x00 0x20 + 32 bytes)
        byte[] p2wsh = new byte[34];
        p2wsh[1] = 0x20;
        PsbtTransaction psbt = buildPsbt(p2wsh, ours);
        try {
            PsbtSigner.sign(psbt, seed, false);
            fail("non-P2WPKH input must be refused");
        } catch (CryptoError e) {
            assertTrue(e.getMessage(), e.getMessage().indexOf("P2WPKH") >= 0);
        }
    }

    @Test
    public void walletKeysFlagOwnedOutputs() throws CryptoError {
        byte[] seed = seed();
        byte[] receive = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/0"));
        byte[] change = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/1/0"));
        byte[] external = HexCodec.decode("0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        PsbtTransaction psbt = buildPsbt(receive, external);
        TxOutput changeOut = new TxOutput();
        changeOut.value = 100L;
        changeOut.scriptPubKey = change;
        psbt.unsignedTx.outputs = new TxOutput[] { psbt.unsignedTx.outputs[0], changeOut };
        psbt.outputs = new PsbtOutput[] { new PsbtOutput(), new PsbtOutput() };

        PsbtSigner.WalletKeys keys = PsbtSigner.deriveWalletKeys(seed, false);
        try {
            boolean[] owned = keys.ownedOutputs(psbt);
            assertFalse("external output must not be owned", owned[0]);
            assertTrue("change output must be owned", owned[1]);
            assertTrue(keys.ownsScript(receive));
            assertFalse(keys.ownsScript(external));
        } finally {
            keys.destroy();
        }

        // Destroyed keys must not sign
        try {
            PsbtSigner.sign(psbt, keys);
            fail("destroyed keys must be unusable");
        } catch (CryptoError e) {
            assertTrue(e.getMessage().indexOf("destroyed") >= 0);
        }
    }

    @Test
    public void signsAllInputsOrNone() throws CryptoError {
        byte[] seed = seed();
        byte[] ours = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/2"));
        byte[] foreign = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/30"));

        TxData prevA = fundingTx(ours, FUNDING_SATS);
        TxData prevB = fundingTx(foreign, FUNDING_SATS);

        TxData tx = new TxData();
        tx.version = 2;
        TxInput inA = new TxInput();
        inA.prevTxHash = TxSerializer.computeTxid(prevA);
        inA.prevIndex = 0;
        inA.scriptSig = new byte[0];
        inA.sequence = 0xffffffffL;
        TxInput inB = new TxInput();
        inB.prevTxHash = TxSerializer.computeTxid(prevB);
        inB.prevIndex = 0;
        inB.scriptSig = new byte[0];
        inB.sequence = 0xffffffffL;
        tx.inputs = new TxInput[] { inA, inB };
        TxOutput out = new TxOutput();
        out.value = 1000L;
        out.scriptPubKey = ours;
        tx.outputs = new TxOutput[] { out };
        tx.locktime = 0;

        PsbtTransaction psbt = new PsbtTransaction();
        psbt.unsignedTxBytes = TxSerializer.serialize(tx);
        psbt.unsignedTx = tx;
        psbt.inputs = new PsbtInput[] { new PsbtInput(), new PsbtInput() };
        psbt.inputs[0].nonWitnessUtxo = TxSerializer.serialize(prevA);
        psbt.inputs[0].witnessUtxoValue = FUNDING_SATS;
        psbt.inputs[0].witnessUtxoScript = ours;
        psbt.inputs[1].nonWitnessUtxo = TxSerializer.serialize(prevB);
        psbt.inputs[1].witnessUtxoValue = FUNDING_SATS;
        psbt.inputs[1].witnessUtxoScript = foreign;
        psbt.outputs = new PsbtOutput[] { new PsbtOutput() };

        try {
            PsbtSigner.sign(psbt, seed, false);
            fail("a foreign input must abort signing");
        } catch (CryptoError e) {
            assertTrue(e.getMessage().indexOf("Input 1") >= 0);
        }
        assertNull("input 0 must not be signed when input 1 is foreign",
            psbt.inputs[0].partialSigValue);

        // Make input B ours too: both get signed
        byte[] ours2 = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/1/1"));
        TxData prevB2 = fundingTx(ours2, FUNDING_SATS);
        inB.prevTxHash = TxSerializer.computeTxid(prevB2);
        psbt.unsignedTxBytes = TxSerializer.serialize(tx);
        psbt.inputs[1].nonWitnessUtxo = TxSerializer.serialize(prevB2);
        psbt.inputs[1].witnessUtxoScript = ours2;
        assertEquals(2, PsbtSigner.sign(psbt, seed, false));
        assertNotNull(psbt.inputs[0].partialSigValue);
        assertNotNull(psbt.inputs[1].partialSigValue);
    }

    @Test
    public void preservesForeignPartialSignature() throws CryptoError {
        byte[] seed = seed();
        byte[] script = p2wpkh(pubKeyHashAt(seed, "m/84'/0'/0'/0/0"));
        PsbtTransaction psbt = buildPsbt(script, script);

        byte[] otherPub = HexCodec.decode(
            "02" + "1111111111111111111111111111111111111111111111111111111111111111");
        byte[] otherSig = HexCodec.decode("3006020101020101" + "01");
        psbt.inputs[0].partialSigKey = otherPub;
        psbt.inputs[0].partialSigValue = otherSig;

        PsbtSigner.sign(psbt, seed, false);

        // Ours is the primary partial sig; the foreign one was moved to unknown
        assertFalse(java.util.Arrays.equals(otherPub, psbt.inputs[0].partialSigKey));
        assertEquals(1, psbt.inputs[0].unknown.size());
        byte[][] pair = (byte[][]) psbt.inputs[0].unknown.elementAt(0);
        assertEquals(0x02, pair[0][0]);
        assertArrayEquals(otherSig, pair[1]);

        // Both survive serialization
        String hex = HexCodec.encode(PsbtSerializer.serialize(psbt));
        assertTrue(hex.indexOf(HexCodec.encode(otherPub)) >= 0);
        assertTrue(hex.indexOf(HexCodec.encode(psbt.inputs[0].partialSigKey)) >= 0);
    }
}
