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

/**
 * Transaction review screen that displays PSBT details before signing.
 *
 * Shows send amount, recipient address, fee (with percentage), and
 * warnings for high fees or multiple recipients. The user can approve
 * ("Sign") or reject ("Reject") the transaction.
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

    private Form form;
    private final Command signCmd;
    private final Command rejectCmd;

    /**
     * Create a new TransactionReviewScreen.
     *
     * @param screens  the screen manager for display control
     * @param listener callback for review events
     * @param psbt     the parsed PSBT transaction to review
     * @param testnet  true for testnet, false for mainnet
     */
    public TransactionReviewScreen(ScreenManager screens,
                                   TransactionReviewListener listener,
                                   PsbtTransaction psbt, boolean testnet) {
        this.screens = screens;
        this.listener = listener;
        this.psbt = psbt;
        this.testnet = testnet;

        signCmd = new Command("Sign", Command.OK, 1);
        rejectCmd = new Command("Reject", Command.BACK, 2);

        buildForm();
    }

    /**
     * Build the review form with transaction details.
     */
    private void buildForm() {
        form = new Form("Review TX");

        long totalOutput = psbt.getTotalOutputValue();
        long fee = psbt.getFee();
        long totalInput = psbt.getTotalInputValue();

        // Send amount (total output)
        form.append(new StringItem("Send:", formatBtc(totalOutput) + " BTC"));

        // Recipient address(es)
        TxOutput[] outputs = psbt.unsignedTx.outputs;
        for (int i = 0; i < outputs.length; i++) {
            try {
                String addr = addressFromScript(outputs[i].scriptPubKey, testnet);
                form.append(new StringItem("To:", formatAddress(addr)));
            } catch (CryptoError e) {
                form.append(new StringItem("To:", "(unknown script)"));
            }
        }

        // Fee display with percentage
        String feeDisplay = formatBtc(fee) + " BTC";
        if (totalInput > 0) {
            long pct = (fee * 100) / totalInput;
            feeDisplay = feeDisplay + " (" + pct + "%)";
        }
        form.append(new StringItem("Fee:", feeDisplay));

        // Warnings
        if (isHighFee(totalInput, fee)) {
            form.append(new StringItem("WARNING:", "Fee >= 10% of input!"));
        }
        if (hasMultipleRecipients(outputs, null)) {
            form.append(new StringItem("WARNING:", "Multiple recipients"));
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
     * No floating point is used.
     *
     * Examples: 100000 -> "0.001", 100000000 -> "1.0", 1000 -> "0.00001", 0 -> "0.0"
     *
     * @param sats amount in satoshis (non-negative)
     * @return BTC-formatted string
     */
    public static String formatBtc(long sats) {
        long wholePart = sats / SATS_PER_BTC;
        long fracPart = sats % SATS_PER_BTC;

        if (fracPart == 0) {
            return String.valueOf(wholePart) + ".0";
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

        return String.valueOf(wholePart) + "." + fullFrac.substring(0, lastNonZero + 1);
    }

    /**
     * Check if the fee is high relative to total input value.
     *
     * @param totalInput total input value in satoshis
     * @param fee        fee in satoshis
     * @return true if fee >= 10% of totalInput
     */
    public static boolean isHighFee(long totalInput, long fee) {
        if (totalInput <= 0) {
            return false;
        }
        // fee >= totalInput / 10  (avoids floating point)
        // Equivalent to: fee * 10 >= totalInput (but watch for overflow)
        // Use: fee >= totalInput / 10
        return fee * 10 >= totalInput;
    }

    /**
     * Check if there are multiple non-change recipients.
     *
     * @param outputs        transaction outputs
     * @param changePubKeyHash 20-byte pubkey hash of the change address, or null
     *                         if no change identification is available
     * @return true if 2 or more outputs are not change
     */
    public static boolean hasMultipleRecipients(TxOutput[] outputs,
                                                 byte[] changePubKeyHash) {
        int recipientCount = 0;
        for (int i = 0; i < outputs.length; i++) {
            if (isChangeOutput(outputs[i], changePubKeyHash)) {
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
     * Check if an output is a change output by comparing its scriptPubKey
     * against the known change pubkey hash.
     */
    private static boolean isChangeOutput(TxOutput output,
                                           byte[] changePubKeyHash) {
        if (changePubKeyHash == null || output.scriptPubKey == null) {
            return false;
        }
        // P2WPKH change: 0x00 0x14 <20-byte-hash>
        if (output.scriptPubKey.length == 22
                && output.scriptPubKey[0] == 0x00
                && output.scriptPubKey[1] == 0x14) {
            byte[] hash = ByteArrayUtils.copyOfRange(output.scriptPubKey, 2, 22);
            return ByteArrayUtils.constantTimeEquals(hash, changePubKeyHash);
        }
        return false;
    }

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
