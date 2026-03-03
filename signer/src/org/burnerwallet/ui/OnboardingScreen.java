package org.burnerwallet.ui;

import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;

import org.burnerwallet.chains.bitcoin.Bip39Mnemonic;
import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.EntropyCollector;

/**
 * Onboarding flow: welcome, wallet generation, wallet import, passphrase.
 *
 * Orchestrates the complete first-run experience:
 * - Welcome screen with Create / Import options
 * - Generate flow: entropy collection, word display, verification
 * - Import flow: word count selection, per-word entry, validation
 * - Passphrase entry (optional)
 *
 * Implements CommandListener for LCDUI commands and
 * EntropyCollector.EntropyListener for entropy readiness callbacks.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class OnboardingScreen implements CommandListener, EntropyCollector.EntropyListener {

    /**
     * Callback interface for onboarding completion.
     */
    public interface OnboardingListener {
        /**
         * Called when the user completes onboarding successfully.
         *
         * @param mnemonic   the validated mnemonic sentence
         * @param passphrase the passphrase (empty string if skipped)
         */
        void onOnboardingComplete(String mnemonic, String passphrase);

        /**
         * Called when the user cancels onboarding (exits from welcome).
         */
        void onOnboardingCancelled();
    }

    private final ScreenManager screens;
    private final OnboardingListener listener;

    // Commands
    private final Command exitCmd;
    private final Command backCmd;
    private final Command okCmd;
    private final Command nextCmd;
    private final Command skipCmd;

    // State
    private String mnemonic;
    private String[] words;
    private int importWordCount;
    private String[] importWords;
    private int importCurrentWord;
    private int verifyIndex;

    // Screens (created on demand)
    private List welcomeScreen;
    private EntropyCollector entropyCollector;

    /**
     * Create a new OnboardingScreen.
     *
     * @param screens  the screen manager for display control
     * @param listener callback for onboarding events
     */
    public OnboardingScreen(ScreenManager screens, OnboardingListener listener) {
        this.screens = screens;
        this.listener = listener;

        this.exitCmd = new Command("Exit", Command.EXIT, 2);
        this.backCmd = new Command("Back", Command.BACK, 2);
        this.okCmd = new Command("OK", Command.OK, 1);
        this.nextCmd = new Command("Next", Command.OK, 1);
        this.skipCmd = new Command("Skip", Command.SCREEN, 3);
    }

    /**
     * Show the welcome screen to start the onboarding flow.
     */
    public void show() {
        showWelcome();
    }

    // ---- Welcome Screen ----

    private void showWelcome() {
        welcomeScreen = new List("Burner Wallet", Choice.IMPLICIT);
        welcomeScreen.append("Create New Wallet", null);
        welcomeScreen.append("Import Existing", null);
        welcomeScreen.addCommand(exitCmd);
        welcomeScreen.setCommandListener(this);
        screens.showScreen(welcomeScreen);
    }

    // ---- Generate Flow ----

    private void startGenerateFlow() {
        entropyCollector = new EntropyCollector();
        entropyCollector.setEntropyListener(this);
        screens.showScreen(entropyCollector);
    }

    /**
     * Called by EntropyCollector when 32 keypresses are collected.
     *
     * @param entropy 32 bytes of SHA-256 hashed timing entropy
     */
    public void onEntropyReady(byte[] entropy) {
        // Take first 16 bytes for 128-bit / 12-word mnemonic
        byte[] entropy16 = ByteArrayUtils.copyOfRange(entropy, 0, 16);
        try {
            mnemonic = Bip39Mnemonic.generate(entropy16);
        } catch (Exception e) {
            screens.showError("Failed to generate mnemonic", welcomeScreen);
            return;
        }
        words = splitWords(mnemonic);
        showWordGroup(0);
    }

    private void showWordGroup(int groupIndex) {
        int startWord = groupIndex * 4;
        int endWord = startWord + 4;
        if (endWord > words.length) {
            endWord = words.length;
        }

        String title = "Words " + (startWord + 1) + "-" + endWord;
        Form wordForm = new Form(title);

        for (int i = startWord; i < endWord; i++) {
            wordForm.append(new StringItem((i + 1) + ". ", words[i]));
        }

        if (groupIndex < 2) {
            wordForm.addCommand(nextCmd);
        } else {
            wordForm.addCommand(okCmd);
        }
        if (groupIndex > 0) {
            wordForm.addCommand(backCmd);
        }
        wordForm.setCommandListener(this);
        screens.showScreen(wordForm);
    }

    private void showVerification() {
        verifyIndex = (int) (System.currentTimeMillis() % words.length);

        Form verifyForm = new Form("Verify Word");
        verifyForm.append(new StringItem(null, "Enter word #" + (verifyIndex + 1) + ":"));
        TextField wordField = new TextField("Word:", "", 32, TextField.ANY);
        verifyForm.append(wordField);
        verifyForm.addCommand(okCmd);
        verifyForm.addCommand(backCmd);
        verifyForm.setCommandListener(this);
        screens.showScreen(verifyForm);
    }

    // ---- Import Flow ----

    private void startImportFlow() {
        showWordCountSelection();
    }

    private void showWordCountSelection() {
        List countList = new List("Word Count", Choice.IMPLICIT);
        countList.append("12 words", null);
        countList.append("24 words", null);
        countList.addCommand(backCmd);
        countList.setCommandListener(this);
        screens.showScreen(countList);
    }

    private void showImportWordEntry(int wordIndex) {
        importCurrentWord = wordIndex;
        String title = "Word " + (wordIndex + 1) + "/" + importWordCount;
        Form wordForm = new Form(title);
        String prefill = "";
        if (importWords[wordIndex] != null) {
            prefill = importWords[wordIndex];
        }
        TextField wordField = new TextField("Word:", prefill, 32, TextField.ANY);
        wordForm.append(wordField);
        wordForm.addCommand(okCmd);
        wordForm.addCommand(backCmd);
        wordForm.setCommandListener(this);
        screens.showScreen(wordForm);
    }

    // ---- Passphrase Screen ----

    private void showPassphraseScreen() {
        Form passphraseForm = new Form("Passphrase");
        passphraseForm.append(new StringItem(null,
                "Enter an optional passphrase for extra security:"));
        TextField passField = new TextField("Passphrase:", "", 64,
                TextField.ANY | TextField.SENSITIVE);
        passphraseForm.append(passField);
        passphraseForm.addCommand(okCmd);
        passphraseForm.addCommand(skipCmd);
        passphraseForm.setCommandListener(this);
        screens.showScreen(passphraseForm);
    }

    // ---- Command Handling ----

    public void commandAction(Command c, Displayable d) {
        // Welcome screen
        if (d == welcomeScreen) {
            handleWelcome(c);
            return;
        }

        // EntropyCollector has no commands; it calls onEntropyReady

        String title = getTitle(d);

        // Word display screens: "Words X-Y"
        if (title != null && title.startsWith("Words ")) {
            handleWordDisplay(c, title);
            return;
        }

        // Verify word screen
        if ("Verify Word".equals(title)) {
            handleVerification(c, d);
            return;
        }

        // Word count selection
        if ("Word Count".equals(title)) {
            handleWordCount(c, d);
            return;
        }

        // Import word entry: "Word N/M"
        if (title != null && title.startsWith("Word ") && title.indexOf('/') != -1) {
            handleImportWord(c, d);
            return;
        }

        // Passphrase screen
        if ("Passphrase".equals(title)) {
            handlePassphrase(c, d);
            return;
        }
    }

    private void handleWelcome(Command c) {
        if (c == exitCmd) {
            listener.onOnboardingCancelled();
            return;
        }
        // List.SELECT_COMMAND for implicit list selection
        if (c == List.SELECT_COMMAND) {
            int idx = welcomeScreen.getSelectedIndex();
            if (idx == 0) {
                startGenerateFlow();
            } else if (idx == 1) {
                startImportFlow();
            }
        }
    }

    private void handleWordDisplay(Command c, String title) {
        if (c == backCmd) {
            // Parse group from title "Words X-Y"
            int groupIndex = parseWordGroupIndex(title);
            if (groupIndex > 0) {
                showWordGroup(groupIndex - 1);
            }
            return;
        }
        if (c == nextCmd) {
            int groupIndex = parseWordGroupIndex(title);
            showWordGroup(groupIndex + 1);
            return;
        }
        if (c == okCmd) {
            // Last group — proceed to verification
            showVerification();
        }
    }

    private void handleVerification(Command c, Displayable d) {
        if (c == backCmd) {
            showWordGroup(2);
            return;
        }
        if (c == okCmd) {
            // Get the entered word from the TextField
            Form form = (Form) d;
            TextField tf = (TextField) form.get(1);
            String entered = tf.getString().trim().toLowerCase();
            String expected = words[verifyIndex].toLowerCase();

            if (entered.equals(expected)) {
                showPassphraseScreen();
            } else {
                clearState();
                screens.showError("Wrong word. Please start over.", welcomeScreen);
                showWelcome();
            }
        }
    }

    private void handleWordCount(Command c, Displayable d) {
        if (c == backCmd) {
            showWelcome();
            return;
        }
        if (c == List.SELECT_COMMAND) {
            List list = (List) d;
            int idx = list.getSelectedIndex();
            if (idx == 0) {
                importWordCount = 12;
            } else {
                importWordCount = 24;
            }
            importWords = new String[importWordCount];
            showImportWordEntry(0);
        }
    }

    private void handleImportWord(Command c, Displayable d) {
        if (c == backCmd) {
            if (importCurrentWord == 0) {
                showWelcome();
            } else {
                showImportWordEntry(importCurrentWord - 1);
            }
            return;
        }
        if (c == okCmd) {
            Form form = (Form) d;
            TextField tf = (TextField) form.get(0);
            String word = tf.getString().trim().toLowerCase();
            if (word.length() == 0) {
                screens.showError("Please enter a word", d);
                return;
            }
            importWords[importCurrentWord] = word;

            if (importCurrentWord + 1 < importWordCount) {
                showImportWordEntry(importCurrentWord + 1);
            } else {
                // All words entered — validate
                StringBuffer sb = new StringBuffer();
                for (int i = 0; i < importWordCount; i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    sb.append(importWords[i]);
                }
                String importedMnemonic = sb.toString();

                if (Bip39Mnemonic.validate(importedMnemonic)) {
                    mnemonic = importedMnemonic;
                    words = splitWords(mnemonic);
                    showPassphraseScreen();
                } else {
                    clearState();
                    screens.showError("Invalid mnemonic. Check words and try again.", welcomeScreen);
                    showWelcome();
                }
            }
        }
    }

    private void handlePassphrase(Command c, Displayable d) {
        if (c == skipCmd) {
            listener.onOnboardingComplete(mnemonic, "");
            return;
        }
        if (c == okCmd) {
            Form form = (Form) d;
            TextField tf = (TextField) form.get(1);
            String passphrase = tf.getString();
            listener.onOnboardingComplete(mnemonic, passphrase);
        }
    }

    // ---- Helpers ----

    /**
     * Get the title of a Displayable (Form or List).
     */
    private String getTitle(Displayable d) {
        if (d instanceof Form) {
            return ((Form) d).getTitle();
        }
        if (d instanceof List) {
            return ((List) d).getTitle();
        }
        return null;
    }

    /**
     * Parse the group index from a title like "Words 1-4" -> 0,
     * "Words 5-8" -> 1, "Words 9-12" -> 2.
     */
    private int parseWordGroupIndex(String title) {
        // Title format: "Words X-Y"
        // Extract X, then compute group = (X - 1) / 4
        int spaceIdx = title.indexOf(' ');
        int dashIdx = title.indexOf('-');
        if (spaceIdx == -1 || dashIdx == -1) {
            return 0;
        }
        try {
            int startWord = Integer.parseInt(title.substring(spaceIdx + 1, dashIdx));
            return (startWord - 1) / 4;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Clear sensitive state when retrying.
     */
    private void clearState() {
        mnemonic = null;
        words = null;
        importWords = null;
        importCurrentWord = 0;
        importWordCount = 0;
    }

    /**
     * Split a mnemonic string into words by spaces.
     * Java 1.4 compatible (no String.split with regex).
     *
     * @param mnemonicStr space-separated words
     * @return array of words
     */
    private String[] splitWords(String mnemonicStr) {
        String trimmed = mnemonicStr.trim();
        if (trimmed.length() == 0) {
            return new String[0];
        }

        int count = 1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == ' ') {
                count++;
            }
        }

        String[] result = new String[count];
        int idx = 0;
        int start = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == ' ') {
                result[idx] = trimmed.substring(start, i);
                idx++;
                start = i + 1;
            }
        }
        result[idx] = trimmed.substring(start);
        return result;
    }
}
