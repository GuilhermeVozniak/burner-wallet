package org.burnerwallet.ui;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;

/**
 * PIN entry screen with three modes: unlock, create, and confirm.
 *
 * Usage:
 *   PinScreen pin = new PinScreen(screens, PinScreen.MODE_ENTER, listener);
 *   screens.showScreen(pin.getForm());
 *
 * For CONFIRM mode, call setFirstPin(String) before showing.
 *
 * In ENTER mode a "Reset" command (with confirmation) lets a user whose
 * store is corrupted or whose PIN is lost wipe the wallet and start over
 * without uninstalling the MIDlet.
 */
public class PinScreen implements CommandListener {

    public static final int MODE_ENTER = 0;
    public static final int MODE_CREATE = 1;
    public static final int MODE_CONFIRM = 2;

    private static final int MIN_PIN_LENGTH = 4;
    private static final int MAX_PIN_LENGTH = 8;

    private final ScreenManager screens;
    private final int mode;
    private final PinListener listener;

    private final Form form;
    private final TextField pinField;
    private final Command okCmd;
    private final Command backCmd;
    private Command resetCmd;

    private String firstPin;

    /**
     * Callback interface for PIN screen events.
     */
    public interface PinListener {
        void onPinEntered(String pin);
        void onPinCreated(String pin);
        void onPinConfirmed(String pin);
        void onPinCancelled();

        /**
         * Called when the user confirms wiping the wallet from the unlock
         * screen (lost PIN / corrupted store recovery path).
         */
        void onPinReset();
    }

    public PinScreen(ScreenManager screens, int mode, PinListener listener) {
        this.screens = screens;
        this.mode = mode;
        this.listener = listener;

        String title;
        if (mode == MODE_CREATE) {
            title = "Set PIN";
        } else if (mode == MODE_CONFIRM) {
            title = "Confirm PIN";
        } else {
            title = "Unlock";
        }

        form = new Form(title);
        pinField = new TextField("PIN:", "", MAX_PIN_LENGTH,
                TextField.NUMERIC | TextField.PASSWORD);
        form.append(pinField);

        okCmd = new Command("OK", Command.OK, 1);
        form.addCommand(okCmd);

        if (mode == MODE_ENTER) {
            backCmd = new Command("Exit", Command.EXIT, 2);
            resetCmd = new Command("Reset wallet", Command.SCREEN, 3);
            form.addCommand(resetCmd);
        } else {
            backCmd = new Command("Back", Command.BACK, 2);
        }
        form.addCommand(backCmd);

        form.setCommandListener(this);
    }

    /**
     * Set the first PIN for comparison in CONFIRM mode.
     * Must be called before showing the form in MODE_CONFIRM.
     */
    public void setFirstPin(String pin) {
        this.firstPin = pin;
    }

    public Displayable getForm() {
        return form;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == okCmd) {
            handleOk();
        } else if (c == backCmd) {
            listener.onPinCancelled();
        } else if (c == resetCmd) {
            showResetConfirmation();
        }
    }

    private void handleOk() {
        String pin = pinField.getString();

        if (pin.length() < MIN_PIN_LENGTH) {
            screens.showError("PIN must be at least 4 digits", form);
            pinField.setString("");
            return;
        }

        if (mode == MODE_ENTER) {
            listener.onPinEntered(pin);
        } else if (mode == MODE_CREATE) {
            listener.onPinCreated(pin);
        } else if (mode == MODE_CONFIRM) {
            if (firstPin != null && pin.equals(firstPin)) {
                listener.onPinConfirmed(pin);
            } else {
                screens.showError("PINs don't match", form);
                pinField.setString("");
            }
        }
    }

    /**
     * Ask for confirmation before wiping the wallet from the unlock screen.
     */
    private void showResetConfirmation() {
        final Form confirm = new Form("Reset Wallet?");
        confirm.append(new StringItem(null,
                "This permanently deletes the wallet on this phone. "
                + "You will need your seed phrase to restore it."));
        final Command wipeCmd = new Command("Wipe", Command.OK, 1);
        final Command cancelCmd = new Command("Cancel", Command.BACK, 2);
        confirm.addCommand(wipeCmd);
        confirm.addCommand(cancelCmd);
        confirm.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == wipeCmd) {
                    listener.onPinReset();
                } else if (c == cancelCmd) {
                    screens.showScreen(form);
                }
            }
        });
        screens.showScreen(confirm);
    }
}
