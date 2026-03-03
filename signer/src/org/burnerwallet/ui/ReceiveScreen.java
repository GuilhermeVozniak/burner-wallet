package org.burnerwallet.ui;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;

import org.burnerwallet.chains.bitcoin.BitcoinAddress;
import org.burnerwallet.chains.bitcoin.Bip44Path;
import org.burnerwallet.core.CryptoError;

/**
 * Receive address screen that derives and displays BIP84 P2WPKH addresses.
 *
 * Shows the current address (formatted for 128px Nokia screen), the derivation
 * path, and the address index. The "Next" command increments the index and
 * derives the next address; "Back" returns to the caller.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class ReceiveScreen implements CommandListener {

    /** Number of characters per line for address display on 128px screen. */
    private static final int ADDR_LINE_WIDTH = 14;

    /**
     * Callback interface for receive screen events.
     */
    public interface ReceiveListener {
        /**
         * Called when the address index changes (user tapped Next).
         *
         * @param newIndex the new address index
         */
        void onAddressIndexChanged(int newIndex);

        /**
         * Called when the user presses Back from the receive screen.
         */
        void onReceiveBack();
    }

    private final ScreenManager screens;
    private final ReceiveListener listener;
    private final byte[] seed;
    private final boolean testnet;

    private int addressIndex;
    private Form form;
    private final Command nextCmd;
    private final Command backCmd;

    /**
     * Create a new ReceiveScreen.
     *
     * @param screens    the screen manager for display control
     * @param listener   callback for receive screen events
     * @param seed       64-byte BIP39 seed
     * @param testnet    true for testnet, false for mainnet
     * @param startIndex initial address index
     */
    public ReceiveScreen(ScreenManager screens, ReceiveListener listener,
                         byte[] seed, boolean testnet, int startIndex) {
        this.screens = screens;
        this.listener = listener;
        this.seed = seed;
        this.testnet = testnet;
        this.addressIndex = startIndex;

        nextCmd = new Command("Next", Command.OK, 1);
        backCmd = new Command("Back", Command.BACK, 2);

        buildForm();
    }

    /**
     * Build (or rebuild) the form with the current address index.
     */
    private void buildForm() {
        form = new Form("Receive");

        try {
            String address = BitcoinAddress.deriveP2wpkhAddress(
                    seed, testnet, 0, false, addressIndex);
            String formatted = formatAddress(address);
            form.append(new StringItem("Address:", formatted));
        } catch (CryptoError e) {
            form.append(new StringItem("Error:", e.getMessage()));
        }

        String path = Bip44Path.bip84Address(testnet, 0, false, addressIndex);
        form.append(new StringItem("Path:", path));
        form.append(new StringItem("Index:", String.valueOf(addressIndex)));

        form.addCommand(nextCmd);
        form.addCommand(backCmd);
        form.setCommandListener(this);
    }

    /**
     * Get the underlying Form displayable.
     *
     * @return the receive screen Form
     */
    public Displayable getScreen() {
        return form;
    }

    /**
     * Get the current address index.
     *
     * @return the current address index
     */
    public int getAddressIndex() {
        return addressIndex;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == nextCmd) {
            addressIndex++;
            buildForm();
            screens.showScreen(form);
            listener.onAddressIndexChanged(addressIndex);
            return;
        }
        if (c == backCmd) {
            listener.onReceiveBack();
        }
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
