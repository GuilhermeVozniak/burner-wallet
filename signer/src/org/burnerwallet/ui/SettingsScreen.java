package org.burnerwallet.ui;

import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;

/**
 * Settings screen with network toggle, wallet wipe, and about info.
 *
 * Displays an implicit List with:
 *   - Network toggle (Mainnet / Testnet)
 *   - Wipe Wallet (with confirmation dialog)
 *   - About (version and description)
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class SettingsScreen implements CommandListener {

    /**
     * Callback interface for settings screen events.
     */
    public interface SettingsListener {
        /**
         * Called when the user toggles the network.
         *
         * @param testnet true if switched to testnet, false if mainnet
         */
        void onNetworkChanged(boolean testnet);

        /**
         * Called when the user confirms wallet wipe.
         */
        void onWipeConfirmed();

        /**
         * Called when the user presses Back from settings.
         */
        void onSettingsBack();
    }

    private final ScreenManager screens;
    private final SettingsListener listener;

    private boolean testnet;
    private List menuList;
    private final Command backCmd;

    /**
     * Create a new SettingsScreen.
     *
     * @param screens  the screen manager for display control
     * @param listener callback for settings events
     * @param testnet  initial network state (true = testnet)
     */
    public SettingsScreen(ScreenManager screens, SettingsListener listener,
                          boolean testnet) {
        this.screens = screens;
        this.listener = listener;
        this.testnet = testnet;

        backCmd = new Command("Back", Command.BACK, 2);

        buildList();
    }

    /**
     * Build (or rebuild) the settings menu list.
     */
    private void buildList() {
        menuList = new List("Settings", Choice.IMPLICIT);

        String networkLabel = testnet ? "Network: Testnet" : "Network: Mainnet";
        menuList.append(networkLabel, null);
        menuList.append("Wipe Wallet", null);
        menuList.append("About", null);

        menuList.addCommand(backCmd);
        menuList.setCommandListener(this);
    }

    /**
     * Get the underlying List displayable.
     *
     * @return the settings screen List
     */
    public Displayable getScreen() {
        return menuList;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == backCmd) {
            listener.onSettingsBack();
            return;
        }
        if (c == List.SELECT_COMMAND) {
            int idx = menuList.getSelectedIndex();
            if (idx == 0) {
                handleNetworkToggle();
            } else if (idx == 1) {
                showWipeConfirmation();
            } else if (idx == 2) {
                showAbout();
            }
        }
    }

    /**
     * Toggle the network between mainnet and testnet.
     */
    private void handleNetworkToggle() {
        testnet = !testnet;
        buildList();
        screens.showScreen(menuList);
        listener.onNetworkChanged(testnet);
    }

    /**
     * Show the wipe wallet confirmation dialog.
     */
    private void showWipeConfirmation() {
        WipeConfirmScreen confirm = new WipeConfirmScreen();
        screens.showScreen(confirm);
    }

    /**
     * Show the about dialog.
     */
    private void showAbout() {
        screens.showModalAlert("About",
                "Burner Wallet v0.1.0\n\nAir-gapped Bitcoin signer\nfor Nokia C1-01",
                AlertType.INFO, menuList);
    }

    /**
     * Inner confirmation screen for wallet wipe.
     * Shows a warning message with Wipe (OK) and Cancel (BACK) commands.
     */
    private class WipeConfirmScreen extends Form implements CommandListener {

        private final Command wipeCmd;
        private final Command cancelCmd;

        WipeConfirmScreen() {
            super("Wipe Wallet?");

            append(new StringItem(null,
                    "This will permanently delete your wallet data. "
                    + "Make sure you have backed up your seed phrase!"));

            wipeCmd = new Command("Wipe", Command.OK, 1);
            cancelCmd = new Command("Cancel", Command.BACK, 2);
            addCommand(wipeCmd);
            addCommand(cancelCmd);
            setCommandListener(this);
        }

        public void commandAction(Command c, Displayable d) {
            if (c == wipeCmd) {
                listener.onWipeConfirmed();
            } else if (c == cancelCmd) {
                screens.showScreen(menuList);
            }
        }
    }
}
