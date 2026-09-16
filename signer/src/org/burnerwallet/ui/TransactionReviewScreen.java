package org.burnerwallet.ui;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;

import org.burnerwallet.chains.bitcoin.NetworkParams;
import org.burnerwallet.chains.bitcoin.PsbtTransaction;
import org.burnerwallet.chains.bitcoin.TxOutput;
import org.burnerwallet.core.Bech32;
import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;

/**
 * Transaction review screen that displays PSBT details before signing.
 *
 * Shows the amount leaving the wallet, every output with its own amount
 * (change back to this wallet is labelled as such and excluded from the
 * send total), the fee with percentage, and warnings for high fees,
 * multiple external recipients, unknown output scripts and unknown input
 * values. The user can approve ("Sign") or reject ("Reject").
 *
 * The caller is expected to have run {@code PsbtSigner.verifyInputs} so
 * the input amounts shown here are the verified ones.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class TransactionReviewScreen implements CommandListener {

    /** Number of characters per line for address display on 128px screen. */
    private static final int ADDR_LINE_WIDTH = 14;

    /** One full bitcoin in satoshis. */
    private static final long SATS_PER_BTC = 100000000L;

    /** Number of decimal places in a BTC amount. */
    private static final int BTC_DECIMALS = 8;

    /**
     * Callback interface for transaction review events.
     */
    public interface TransactionReviewListener {
        /**
         * Called when the user approves the transaction for signing.
         *
         * @param psbt the approved PSBT transaction
         */
        void onApprove(PsbtTransaction psbt);

        /**
         * Called when the user rejects the transaction.
         */
        void onReject();
    }

    private final ScreenManager screens;
    private final TransactionReviewListener listener;
    private final PsbtTransaction psbt;
    private final boolean testnet;
    private final boolean[] ownedOutputs;

    private Form form;
    private final Command signCmd;
    private final Command rejectCmd;

    /**
     * Create a new TransactionReviewScreen.
     *
     * @param screens      the screen manager for display control
     * @param listener     callback for review events
     * @param psbt         the parsed PSBT transaction to review
     * @param testnet      true for testnet, false for mainnet
     * @param ownedOutputs one flag per output: true if it pays back to
     *                     this wallet (change); null if unknown
     */
    public TransactionReviewScreen(ScreenManager screens,
                                   TransactionReviewListener listener,
                                   PsbtTransaction psbt, boolean testnet,
                                   boolean[] ownedOutputs) {
        this.screens = screens;
        this.listener = listener;
        this.psbt = psbt;
        this.testnet = testnet;
        this.ownedOutputs = ownedOutputs;

        signCmd = new Command("Sign", Command.OK, 1);
        rejectCmd = new Command("Reject", Command.BACK, 2);

        buildForm();
    }

    /**
     * Build the review form with transaction details.
     */
    private void buildForm() {
        form = new Form("Review TX");

        TxOutput[] outputs = psbt.unsignedTx.outputs;
        boolean inputsKnown = psbt.allInputsHaveWitnessUtxo();
        long totalInput = psbt.getTotalInputValue();
        long totalOutput = psbt.getTotalOutputValue();
        long fee = totalInput - totalOutput;
        long sendAmount = sumExternalOutputs(outputs, ownedOutputs);

        // Amount leaving the wallet (excludes change)
        form.append(new StringItem("Send:", formatBtc(sendAmount) + " BTC"));

        // Every output with its own amount
        boolean unknownScript = false;
        for (int i = 0; i < outputs.length; i++) {
            boolean owned = ownedOutputs != null && i < ownedOutputs.length
                    && ownedOutputs[i];
            String label = owned ? "Change:" : "To:";
            String amount = formatBtc(outputs[i].value) + " BTC";
            try {
                String addr = addressFromScript(outputs[i].scriptPubKey, testnet);
                form.append(new StringItem(label, amount + "\n" + formatAddress(addr)));
            } catch (CryptoError e) {
                unknownScript = true;
                form.append(new StringItem(label, amount + "\nUNKNOWN SCRIPT\n"
                        + HexCodec.encode(outputs[i].scriptPubKey)));
            }
        }

        // Fee display with percentage
        if (inputsKnown) {
            String feeDisplay = formatBtc(fee) + " BTC";
            if (totalInput > 0 && fee >= 0) {
                feeDisplay = feeDisplay + " (" + formatFeePercent(totalInput, fee) + ")";
            }
            form.append(new StringItem("Fee:", feeDisplay));
        } else {
            form.append(new StringItem("Fee:", "UNKNOWN"));
        }

        // Warnings
        if (!inputsKnown) {
            form.append(new StringItem("WARNING:", "Input value unknown!"));
        }
        if (inputsKnown && isHighFee(totalInput, fee)) {
            form.append(new StringItem("WARNING:", "Fee >= 10% of input!"));
        }
        if (hasMultipleRecipients(outputs, ownedOutputs)) {
            form.append(new StringItem("WARNING:", "Multiple recipients"));
        }
        if (unknownScript) {
            form.append(new StringItem("WARNING:", "Unknown output type"));
        }

        form.addCommand(signCmd);
        form.addCommand(rejectCmd);
        form.setCommandListener(this);
    }

    /**
     * Get the underlying Form displayable.
     *
     * @return the review screen Form
     */
    public Displayable getScreen() {
        return form;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == signCmd) {
            listener.onApprove(psbt);
            return;
        }
        if (c == rejectCmd) {
            listener.onReject();
        }
    }

    // ---- Static helpers (testable without LCDUI) ----

    /**
     * Format satoshis as a BTC string using integer-only arithmetic.
     * No floating point is used. Negative values get a leading '-'.
     *
     * Examples: 100000 -> "0.001", 100000000 -> "1.0", 1000 -> "0.00001",
     * 0 -> "0.0", -50000 -> "-0.0005"
     *
     * @param sats amount in satoshis
     * @return BTC-formatted string
     */
    public static String formatBtc(long sats) {
        boolean negative = sats < 0;
        long abs = negative ? -sats : sats;
        long wholePart = abs / SATS_PER_BTC;
        long fracPart = abs % SATS_PER_BTC;
        String sign = negative ? "-" : "";

        if (fracPart == 0) {
            return sign + String.valueOf(wholePart) + ".0";
        }

        // Build fractional part with leading zeros, then strip trailing zeros
        // fracPart is 0..99999999 and we need exactly 8 digits
        String fracStr = String.valueOf(fracPart);

        // Pad with leading zeros to 8 digits
        StringBuffer padded = new StringBuffer();
        for (int i = fracStr.length(); i < BTC_DECIMALS; i++) {
            padded.append('0');
        }
        padded.append(fracStr);
        String fullFrac = padded.toString();

        // Strip trailing zeros
        int lastNonZero = fullFrac.length() - 1;
        while (lastNonZero > 0 && fullFrac.charAt(lastNonZero) == '0') {
            lastNonZero--;
        }

        return sign + String.valueOf(wholePart) + "."
                + fullFrac.substring(0, lastNonZero + 1);
    }

    /**
     * Format the fee as a percentage of the total input with one decimal,
     * using integer arithmetic only. Sub-0.1% fees show as "<0.1%" rather
     * than a misleading "0%".
     *
     * Examples: (200000, 1000) -> "0.5%", (100000, 10000) -> "10.0%",
     * (1000000, 10) -> "<0.1%", (1000, 0) -> "0.0%"
     *
     * @param totalInput total input value in satoshis (must be > 0)
     * @param fee        fee in satoshis (must be >= 0)
     * @return percentage string with a trailing '%'
     */
    public static String formatFeePercent(long totalInput, long fee) {
        if (totalInput <= 0 || fee < 0) {
            return "?%";
        }
        // Tenths of a percent; fee * 1000 cannot overflow for any real amount
        long tenths = (fee * 1000) / totalInput;
        if (tenths == 0 && fee > 0) {
            return "<0.1%";
        }
        return (tenths / 10) + "." + (tenths % 10) + "%";
    }

    /**
     * Check if the fee is high relative to total input value.
     *
     * @param totalInput total input value in satoshis
     * @param fee        fee in satoshis
     * @return true if fee >= 10% of totalInput
     */
    public static boolean isHighFee(long totalInput, long fee) {
        if (totalInput <= 0 || fee < 0) {
            return false;
        }
        // fee >= totalInput / 10 without floating point or overflow
        return fee >= totalInput / 10;
    }

    /**
     * Sum of outputs that leave the wallet (not flagged as owned).
     *
     * @param outputs      transaction outputs
     * @param ownedOutputs per-output ownership flags, or null for none owned
     * @return satoshis sent to external recipients
     */
    public static long sumExternalOutputs(TxOutput[] outputs, boolean[] ownedOutputs) {
        long total = 0;
        for (int i = 0; i < outputs.length; i++) {
            boolean owned = ownedOutputs != null && i < ownedOutputs.length
                    && ownedOutputs[i];
            if (!owned) {
                total += outputs[i].value;
            }
        }
        return total;
    }

    /**
     * Check if there are multiple external (non-owned) recipients.
     *
     * @param outputs      transaction outputs
     * @param ownedOutputs per-output ownership flags, or null for none owned
     * @return true if 2 or more outputs are not owned by this wallet
     */
    public static boolean hasMultipleRecipients(TxOutput[] outputs,
                                                 boolean[] ownedOutputs) {
        int recipientCount = 0;
        for (int i = 0; i < outputs.length; i++) {
            boolean owned = ownedOutputs != null && i < ownedOutputs.length
                    && ownedOutputs[i];
            if (owned) {
                continue;
            }
            recipientCount++;
            if (recipientCount >= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract a bech32 address from a P2WPKH scriptPubKey.
     *
     * P2WPKH script format: 0x00 0x14 [20-byte witness program]
     *
     * @param scriptPubKey the output script bytes
     * @param testnet      true for testnet, false for mainnet
     * @return bech32 address string
     * @throws CryptoError if the script is not a valid P2WPKH script
     */
    public static String addressFromScript(byte[] scriptPubKey, boolean testnet)
            throws CryptoError {
        if (scriptPubKey == null || scriptPubKey.length < 2) {
            throw new CryptoError(CryptoError.ERR_ENCODING,
                "Script too short for address extraction");
        }

        int witnessVersion = scriptPubKey[0] & 0xFF;
        int pushLen = scriptPubKey[1] & 0xFF;

        // P2WPKH: OP_0 (0x00) + OP_PUSHBYTES_20 (0x14) + 20 bytes
        if (witnessVersion == 0x00 && pushLen == 0x14
                && scriptPubKey.length == 22) {
            byte[] witnessProgram = ByteArrayUtils.copyOfRange(scriptPubKey, 2, 22);
            String hrp = testnet
                ? NetworkParams.TESTNET_BECH32_HRP
                : NetworkParams.MAINNET_BECH32_HRP;
            return Bech32.encode(hrp, 0, witnessProgram);
        }

        throw new CryptoError(CryptoError.ERR_ENCODING,
            "Unsupported script type for address extraction");
    }

    // ---- Private helpers ----

    /**
     * Format a bech32 address for display on a 128px Nokia screen.
     * Splits the address every 14 characters with newline separators.
     *
     * @param address the bech32 address string
     * @return the formatted address with newlines
     */
    private String formatAddress(String address) {
        if (address == null || address.length() <= ADDR_LINE_WIDTH) {
            return address;
        }

        StringBuffer sb = new StringBuffer();
        int len = address.length();
        for (int i = 0; i < len; i += ADDR_LINE_WIDTH) {
            if (i > 0) {
                sb.append('\n');
            }
            int end = i + ADDR_LINE_WIDTH;
            if (end > len) {
                end = len;
            }
            sb.append(address.substring(i, end));
        }
        return sb.toString();
    }
}
