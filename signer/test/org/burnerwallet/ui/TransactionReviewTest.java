package org.burnerwallet.ui;

import org.burnerwallet.chains.bitcoin.TxOutput;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for TransactionReviewScreen static helpers.
 *
 * Tests formatBtc, isHighFee, hasMultipleRecipients, and addressFromScript
 * without requiring LCDUI or ScreenManager.
 */
public class TransactionReviewTest {

    @Test
    public void formatSatsToBtc() {
        assertEquals("0.001", TransactionReviewScreen.formatBtc(100000));
        assertEquals("1.0", TransactionReviewScreen.formatBtc(100000000));
        assertEquals("0.00001", TransactionReviewScreen.formatBtc(1000));
        assertEquals("0.0", TransactionReviewScreen.formatBtc(0));
    }

    @Test
    public void detectHighFee() {
        assertTrue(TransactionReviewScreen.isHighFee(10000, 1000));
        assertFalse(TransactionReviewScreen.isHighFee(100000, 500));
    }

    @Test
    public void detectMultipleRecipients() throws CryptoError {
        TxOutput out1 = new TxOutput();
        out1.scriptPubKey = HexCodec.decode(
            "0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        TxOutput out2 = new TxOutput();
        out2.scriptPubKey = HexCodec.decode(
            "0014bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertTrue(TransactionReviewScreen.hasMultipleRecipients(
            new TxOutput[]{out1, out2}, null));
    }

    @Test
    public void extractAddressFromP2wpkh() throws CryptoError {
        byte[] script = HexCodec.decode(
            "0014751e76e8199196d454941c45d1b3a323f1433bd6");
        String addr = TransactionReviewScreen.addressFromScript(script, false);
        assertTrue(addr.startsWith("bc1q"));
    }
}
