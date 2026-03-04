package org.burnerwallet.ui;

import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;

/**
 * Main wallet home screen with options for Receive Address, Settings, and Lock.
 *
 * Displays an implicit List with the primary wallet actions.
 * Delegates all user actions to a HomeListener callback.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class WalletHomeScreen implements CommandListener {

    /** Action constant for Receive Address. */
    public static final int ACTION_RECEIVE = 0;

    /** Action constant for Sign Transaction. */
    public static final int ACTION_SIGN = 2;

    /** Action constant for Settings. */
    public static final int ACTION_SETTINGS = 1;

    /** Action constant for Lock (exit). */
    public static final int ACTION_LOCK = -1;

    /**
     * Callback interface for home screen actions.
     */
    public interface HomeListener {
        /**
         * Called when the user selects a home screen action.
         *
         * @param action ACTION_RECEIVE (0), ACTION_SIGN (2),
         *               ACTION_SETTINGS (1), or ACTION_LOCK (-1)
         */
        void onHomeAction(int action);
    }

    private final ScreenManager screens;
    private final HomeListener listener;
    private final List menuList;
    private final Command lockCmd;

    /**
     * Create a new WalletHomeScreen.
     *
     * @param screens  the screen manager for display control
     * @param listener callback for home screen events
     */
    public WalletHomeScreen(ScreenManager screens, HomeListener listener) {
        this.screens = screens;
        this.listener = listener;

        menuList = new List("Burner Wallet", Choice.IMPLICIT);
        menuList.append("Receive Address", null);   // index 0
        menuList.append("Sign Transaction", null);   // index 1
        menuList.append("Settings", null);            // index 2

        lockCmd = new Command("Lock", Command.EXIT, 2);
        menuList.addCommand(lockCmd);

        menuList.setCommandListener(this);
    }

    /**
     * Get the underlying List displayable.
     *
     * @return the home screen List
     */
    public Displayable getScreen() {
        return menuList;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == lockCmd) {
            listener.onHomeAction(ACTION_LOCK);
            return;
        }
        if (c == List.SELECT_COMMAND) {
            int idx = menuList.getSelectedIndex();
            if (idx == 0) {
                listener.onHomeAction(ACTION_RECEIVE);
            } else if (idx == 1) {
                listener.onHomeAction(ACTION_SIGN);
            } else if (idx == 2) {
                listener.onHomeAction(ACTION_SETTINGS);
            }
        }
    }
}
