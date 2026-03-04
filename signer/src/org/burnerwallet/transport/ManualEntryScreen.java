package org.burnerwallet.transport;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.TextField;

import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HexCodec;
import org.burnerwallet.ui.ScreenManager;

/**
 * Manual hex entry screen for PSBT data.
 *
 * Provides a fallback input method when QR scanning is not available.
 * The user types (or pastes) a hex-encoded PSBT string. On submit,
 * the screen validates hex encoding and checks for the PSBT magic
 * prefix ({@code 70736274ff}) before passing the decoded bytes to
 * the listener.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class ManualEntryScreen implements CommandListener {

    /** PSBT magic bytes: "psbt" + 0xff = 70736274ff. */
    private static final String PSBT_MAGIC_HEX = "70736274ff";

    /** Minimum hex length for a valid PSBT (magic = 5 bytes = 10 hex chars). */
    private static final int MIN_HEX_LENGTH = 10;

    /**
     * Callback interface for manual entry events.
     */
    public interface ManualEntryListener {
        /**
         * Called when the user submits a valid hex-encoded PSBT.
         *
         * @param psbt the decoded PSBT bytes
         */
        void onPsbtEntered(byte[] psbt);

        /**
         * Called when the user cancels manual entry.
         */
        void onManualEntryCancelled();
    }

    private final ScreenManager screens;
    private final ManualEntryListener listener;

    private final Form form;
    private final TextField hexField;
    private final Command submitCmd;
    private final Command cancelCmd;

    /**
     * Create a new ManualEntryScreen.
     *
     * @param screens  the screen manager for display control
     * @param listener callback for manual entry events
     */
    public ManualEntryScreen(ScreenManager screens, ManualEntryListener listener) {
        this.screens = screens;
        this.listener = listener;

        form = new Form("Enter PSBT");
        hexField = new TextField("Hex:", "", 4000, TextField.ANY);
        form.append(hexField);

        submitCmd = new Command("Submit", Command.OK, 1);
        cancelCmd = new Command("Cancel", Command.BACK, 2);
        form.addCommand(submitCmd);
        form.addCommand(cancelCmd);
        form.setCommandListener(this);
    }

    /**
     * Get the underlying Form displayable.
     *
     * @return the manual entry Form
     */
    public Displayable getScreen() {
        return form;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == submitCmd) {
            handleSubmit();
        } else if (c == cancelCmd) {
            listener.onManualEntryCancelled();
        }
    }

    private void handleSubmit() {
        String hex = hexField.getString().trim();

        // Strip any whitespace or newlines the user may have included
        StringBuffer cleaned = new StringBuffer();
        for (int i = 0; i < hex.length(); i++) {
            char ch = hex.charAt(i);
            if (ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') {
                cleaned.append(ch);
            }
        }
        hex = cleaned.toString().toLowerCase();

        // Check minimum length
        if (hex.length() < MIN_HEX_LENGTH) {
            screens.showError("Input too short", form);
            return;
        }

        // Check even length (required for hex decoding)
        if (hex.length() % 2 != 0) {
            screens.showError("Hex must have even length", form);
            return;
        }

        // Decode hex
        byte[] bytes;
        try {
            bytes = HexCodec.decode(hex);
        } catch (CryptoError e) {
            screens.showError("Invalid hex: " + e.getMessage(), form);
            return;
        }

        // Check PSBT magic prefix: 70736274ff
        if (!hex.startsWith(PSBT_MAGIC_HEX)) {
            screens.showError("Not a PSBT (missing magic prefix)", form);
            return;
        }

        listener.onPsbtEntered(bytes);
    }
}
