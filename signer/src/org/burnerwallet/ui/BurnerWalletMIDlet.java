package org.burnerwallet.ui;

import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

import org.burnerwallet.chains.bitcoin.Bip39Mnemonic;
import org.burnerwallet.chains.bitcoin.PsbtParser;
import org.burnerwallet.chains.bitcoin.PsbtSerializer;
import org.burnerwallet.chains.bitcoin.PsbtSigner;
import org.burnerwallet.chains.bitcoin.PsbtTransaction;
import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.storage.MidpRecordStoreAdapter;
import org.burnerwallet.storage.UnlockResult;
import org.burnerwallet.storage.WalletStore;
import org.burnerwallet.transport.CameraScanner;
import org.burnerwallet.transport.ManualEntryScreen;

/**
 * Main MIDlet entry point for the Burner Wallet signer.
 *
 * Wires up the complete lifecycle: onboarding, PIN entry/create/confirm,
 * wallet home, receive address, settings, PSBT signing, and QR display.
 * Implements all listener interfaces to coordinate navigation and storage.
 *
 * Every error path shows the message on top of a concrete next screen
 * (never {@code null}), so a failure inside a command handler can never
 * take the MIDlet down.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class BurnerWalletMIDlet extends MIDlet
        implements PinScreen.PinListener,
                   OnboardingScreen.OnboardingListener,
                   WalletHomeScreen.HomeListener,
                   ReceiveScreen.ReceiveListener,
                   SettingsScreen.SettingsListener,
                   TransactionReviewScreen.TransactionReviewListener,
                   QrDisplayScreen.QrDisplayListener,
                   QrScanScreen.QrScanListener,
                   ManualEntryScreen.ManualEntryListener {

    private ScreenManager screens;
    private WalletStore walletStore;
    private boolean initialized;

    /** 64-byte BIP39 seed, held in memory while unlocked. */
    private byte[] currentSeed;

    /** BIP39 passphrase, held in memory during onboarding and after unlock. */
    private String currentPassphrase;

    /** PIN from the CREATE step, held until CONFIRM completes. */
    private String pendingPin;

    /** Wallet keys derived for the transaction under review. */
    private PsbtSigner.WalletKeys pendingKeys;

    /** Canvases with timers/camera that must be released on pause. */
    private QrDisplayScreen activeQrDisplay;
    private QrScanScreen activeQrScan;

    protected void startApp() throws MIDletStateChangeException {
        if (!initialized) {
            initialized = true;
            screens = new ScreenManager(this);
            walletStore = new WalletStore(new MidpRecordStoreAdapter());
        }

        if (walletStore.walletExists()) {
            showPinEntry();
        } else {
            showOnboarding();
        }
    }

    protected void pauseApp() {
        releaseScreens();
        wipeSensitiveData();
    }

    protected void destroyApp(boolean unconditional)
            throws MIDletStateChangeException {
        releaseScreens();
        wipeSensitiveData();
    }

    // ---- Navigation helpers ----

    private Displayable pinEntryScreen() {
        return new PinScreen(screens, PinScreen.MODE_ENTER, this).getForm();
    }

    private Displayable homeScreen() {
        return new WalletHomeScreen(screens, this).getScreen();
    }

    private void showPinEntry() {
        screens.showScreen(pinEntryScreen());
    }

    private void showPinCreate() {
        PinScreen pin = new PinScreen(screens, PinScreen.MODE_CREATE, this);
        screens.showScreen(pin.getForm());
    }

    private void showPinConfirm() {
        PinScreen pin = new PinScreen(screens, PinScreen.MODE_CONFIRM, this);
        pin.setFirstPin(pendingPin);
        screens.showScreen(pin.getForm());
    }

    private void showOnboarding() {
        OnboardingScreen onboarding = new OnboardingScreen(screens, this);
        onboarding.show();
    }

    private void showHome() {
        screens.showScreen(homeScreen());
    }

    /** Show an error alert, then the wallet home screen. */
    private void errorToHome(String message) {
        screens.showError(message, homeScreen());
    }

    /** Show an error alert, then the PIN entry screen. */
    private void errorToPin(String message) {
        screens.showError(message, pinEntryScreen());
    }

    /** Show the onboarding welcome screen, then an error alert on top. */
    private void errorToOnboarding(String message) {
        showOnboarding();
        screens.showError(message, null);
    }

    // ---- OnboardingListener ----

    public void onOnboardingComplete(String mnemonic, String passphrase) {
        currentSeed = Bip39Mnemonic.toSeed(mnemonic, passphrase);
        currentPassphrase = passphrase;
        showPinCreate();
    }

    public void onOnboardingCancelled() {
        screens.exit();
    }

    // ---- PinListener ----

    public void onPinEntered(String pin) {
        try {
            UnlockResult result = walletStore.unlockFull(pin);
            if (result != null) {
                currentSeed = result.seed;
                currentPassphrase = result.passphrase;
                showHome();
            } else if (!walletStore.walletExists()) {
                // Attempt limit reached: the store wiped itself
                wipeSensitiveData();
                errorToOnboarding("Too many wrong PINs. Wallet wiped.");
            } else {
                int left = WalletStore.MAX_FAILED_ATTEMPTS
                        - walletStore.getFailedAttempts();
                errorToPin("Wrong PIN (" + left + " attempts left)");
            }
        } catch (Exception e) {
            errorToPin("Unlock failed: " + e.getMessage()
                    + ". Use 'Reset wallet' if the store is corrupted.");
        }
    }

    public void onPinCreated(String pin) {
        pendingPin = pin;
        showPinConfirm();
    }

    public void onPinConfirmed(String pin) {
        try {
            walletStore.createWallet(
                    currentSeed, currentPassphrase, pin, false);
            pendingPin = null;
            screens.showInfo("Wallet created!", homeScreen());
        } catch (CryptoError e) {
            errorToOnboarding("Wallet creation failed: " + e.getMessage());
        } catch (Exception e) {
            errorToOnboarding("Storage error: " + e.getMessage());
        }
    }

    public void onPinCancelled() {
        if (walletStore.walletExists()) {
            screens.exit();
        } else {
            showOnboarding();
        }
    }

    public void onPinReset() {
        try {
            walletStore.wipe();
        } catch (Exception e) {
            // Store may already be gone or unreadable; onboarding follows anyway
        }
        wipeSensitiveData();
        showOnboarding();
    }

    // ---- HomeListener ----

    public void onHomeAction(int action) {
        if (action == WalletHomeScreen.ACTION_RECEIVE) {
            try {
                boolean testnet = walletStore.isTestnet();
                int index = walletStore.getAddressIndex();
                ReceiveScreen receive = new ReceiveScreen(
                        screens, this, currentSeed, testnet, index);
                screens.showScreen(receive.getScreen());
            } catch (Exception e) {
                errorToHome("Failed to load address: " + e.getMessage());
            }
        } else if (action == WalletHomeScreen.ACTION_SIGN) {
            CameraScanner probe = new CameraScanner();
            if (probe.isAvailable()) {
                QrScanScreen scan = new QrScanScreen(screens, this);
                activeQrScan = scan;
                screens.showScreen(scan.getScreen());
                scan.startScanning();
            } else {
                ManualEntryScreen entry =
                        new ManualEntryScreen(screens, this);
                screens.showScreen(entry.getScreen());
            }
        } else if (action == WalletHomeScreen.ACTION_SETTINGS) {
            try {
                boolean testnet = walletStore.isTestnet();
                SettingsScreen settings = new SettingsScreen(
                        screens, this, testnet);
                screens.showScreen(settings.getScreen());
            } catch (Exception e) {
                errorToHome("Failed to load settings: " + e.getMessage());
            }
        } else if (action == WalletHomeScreen.ACTION_LOCK) {
            wipeSensitiveData();
            showPinEntry();
        }
    }

    // ---- ReceiveListener ----

    public void onAddressIndexChanged(int newIndex) {
        try {
            walletStore.setAddressIndex(newIndex);
        } catch (Exception e) {
            // Best-effort persist; index is still in memory on ReceiveScreen
        }
    }

    public void onShowQr(String address) {
        try {
            // Plain text QR (no multi-frame header) so any wallet can scan it
            QrDisplayScreen qrScreen = new QrDisplayScreen(
                    screens, this, address, "Receive Address");
            activeQrDisplay = qrScreen;
            screens.showScreen(qrScreen.getScreen());
        } catch (Exception e) {
            errorToHome("Failed to show QR: " + e.getMessage());
        }
    }

    public void onReceiveBack() {
        showHome();
    }

    // ---- SettingsListener ----

    public void onNetworkChanged(boolean testnet) {
        try {
            walletStore.setTestnet(testnet);
        } catch (Exception e) {
            // Best-effort persist
        }
    }

    public void onWipeConfirmed() {
        try {
            walletStore.wipe();
        } catch (Exception e) {
            // A failed wipe must not look like success: the old wallet
            // (and PIN) would still be on the phone.
            errorToHome("Wipe failed: " + e.getMessage());
            return;
        }
        wipeSensitiveData();
        showOnboarding();
    }

    public void onSettingsBack() {
        showHome();
    }

    // ---- QrScanListener ----

    public void onScanComplete(byte[] payload) {
        activeQrScan = null;
        onPsbtEntered(payload);
    }

    public void onScanCancelled() {
        activeQrScan = null;
        // Fall back to manual entry when scan is cancelled
        ManualEntryScreen entry = new ManualEntryScreen(screens, this);
        screens.showScreen(entry.getScreen());
    }

    // ---- ManualEntryListener ----

    public void onPsbtEntered(byte[] psbt) {
        releaseKeys();
        try {
            boolean testnet = walletStore.isTestnet();
            PsbtTransaction tx = PsbtParser.parse(psbt);

            // Enforce the signing policy (SIGHASH_ALL, verified previous
            // transactions) before anything is shown to the user.
            PsbtSigner.verifyInputs(tx);

            // Derive the wallet keys once: used to label change outputs
            // now and to sign after approval.
            pendingKeys = PsbtSigner.deriveWalletKeys(currentSeed, testnet);
            boolean[] owned = pendingKeys.ownedOutputs(tx);

            TransactionReviewScreen review = new TransactionReviewScreen(
                    screens, this, tx, testnet, owned);
            screens.showScreen(review.getScreen());
        } catch (CryptoError e) {
            releaseKeys();
            errorToHome("PSBT rejected: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            releaseKeys();
            errorToHome("PSBT too large for this device");
        } catch (Exception e) {
            releaseKeys();
            errorToHome("Failed to parse PSBT: " + e.getMessage());
        }
    }

    public void onManualEntryCancelled() {
        showHome();
    }

    // ---- TransactionReviewListener ----

    public void onApprove(PsbtTransaction psbt) {
        try {
            boolean testnet = walletStore.isTestnet();
            if (pendingKeys == null) {
                pendingKeys = PsbtSigner.deriveWalletKeys(currentSeed, testnet);
            }
            int signed = PsbtSigner.sign(psbt, pendingKeys);
            releaseKeys();
            if (signed == 0) {
                throw new CryptoError(CryptoError.ERR_PSBT, "No inputs were signed");
            }
            byte[] signedBytes = PsbtSerializer.serialize(psbt);
            QrDisplayScreen qrScreen = new QrDisplayScreen(
                    screens, this, signedBytes, "Signed PSBT");
            activeQrDisplay = qrScreen;
            screens.showScreen(qrScreen.getScreen());
        } catch (CryptoError e) {
            errorToHome("Signing failed: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            errorToHome("Signed PSBT too large for QR: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            errorToHome("Out of memory while signing");
        } catch (Exception e) {
            errorToHome("Signing error: " + e.getMessage());
        } finally {
            releaseKeys();
        }
    }

    public void onReject() {
        releaseKeys();
        showHome();
    }

    // ---- QrDisplayListener ----

    public void onQrDisplayDone() {
        activeQrDisplay = null;
        showHome();
    }

    // ---- Security ----

    /**
     * Stop timers and release the camera of any canvas still running.
     */
    private void releaseScreens() {
        if (activeQrDisplay != null) {
            activeQrDisplay.destroy();
            activeQrDisplay = null;
        }
        if (activeQrScan != null) {
            activeQrScan.destroy();
            activeQrScan = null;
        }
    }

    /**
     * Zero the derived signing keys, if any.
     */
    private void releaseKeys() {
        if (pendingKeys != null) {
            pendingKeys.destroy();
            pendingKeys = null;
        }
    }

    /**
     * Zero-fill all sensitive in-memory data.
     */
    private void wipeSensitiveData() {
        releaseKeys();
        if (currentSeed != null) {
            ByteArrayUtils.zeroFill(currentSeed);
            currentSeed = null;
        }
        currentPassphrase = null;
        pendingPin = null;
    }
}
