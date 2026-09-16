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
        assertEquals("2.5", TransactionReviewScreen.formatBtc(250000000));
    }

    @Test
    public void formatNegativeSats() {
        // A negative fee (input value unknown) must not print garbage digits
        assertEquals("-0.0005", TransactionReviewScreen.formatBtc(-50000));
        assertEquals("-1.5", TransactionReviewScreen.formatBtc(-150000000));
    }

    @Test
    public void formatFeePercentWithOneDecimal() {
        assertEquals("0.5%", TransactionReviewScreen.formatFeePercent(200000, 1000));
        assertEquals("10.0%", TransactionReviewScreen.formatFeePercent(100000, 10000));
        assertEquals("0.0%", TransactionReviewScreen.formatFeePercent(1000, 0));
        assertEquals("<0.1%", TransactionReviewScreen.formatFeePercent(1000000, 10));
        assertEquals("100.0%", TransactionReviewScreen.formatFeePercent(5000, 5000));
        assertEquals("?%", TransactionReviewScreen.formatFeePercent(0, 10));
    }

    @Test
    public void detectHighFee() {
        assertTrue(TransactionReviewScreen.isHighFee(10000, 1000));
        assertFalse(TransactionReviewScreen.isHighFee(100000, 500));
        assertFalse("negative fee is 'unknown', not 'high'",
            TransactionReviewScreen.isHighFee(100000, -1));
    }

    @Test
    public void detectMultipleRecipients() throws CryptoError {
        TxOutput out1 = new TxOutput();
        out1.value = 1000;
        out1.scriptPubKey = HexCodec.decode(
            "0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        TxOutput out2 = new TxOutput();
        out2.value = 2000;
        out2.scriptPubKey = HexCodec.decode(
            "0014bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        TxOutput[] outputs = new TxOutput[]{out1, out2};

        assertTrue(TransactionReviewScreen.hasMultipleRecipients(outputs, null));
        // Recipient + change is a single recipient
        assertFalse(TransactionReviewScreen.hasMultipleRecipients(
            outputs, new boolean[]{false, true}));
        assertEquals(1000, TransactionReviewScreen.sumExternalOutputs(
            outputs, new boolean[]{false, true}));
        assertEquals(3000, TransactionReviewScreen.sumExternalOutputs(outputs, null));
    }

    @Test
    public void extractAddressFromP2wpkh() throws CryptoError {
        byte[] script = HexCodec.decode(
            "0014751e76e8199196d454941c45d1b3a323f1433bd6");
        String addr = TransactionReviewScreen.addressFromScript(script, false);
        assertTrue(addr.startsWith("bc1q"));
    }
}
