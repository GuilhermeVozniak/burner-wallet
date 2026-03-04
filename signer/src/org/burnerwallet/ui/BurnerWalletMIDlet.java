package org.burnerwallet.ui;

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
import org.burnerwallet.storage.WalletStore;
import org.burnerwallet.transport.ManualEntryScreen;

/**
 * Main MIDlet entry point for the Burner Wallet signer.
 *
 * Wires up the complete lifecycle: onboarding, PIN entry/create/confirm,
 * wallet home, receive address, settings, PSBT signing, and QR display.
 * Implements all listener interfaces to coordinate navigation and storage.
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
        wipeSensitiveData();
    }

    protected void destroyApp(boolean unconditional)
            throws MIDletStateChangeException {
        wipeSensitiveData();
    }

    // ---- Navigation helpers ----

    private void showPinEntry() {
        PinScreen pin = new PinScreen(screens, PinScreen.MODE_ENTER, this);
        screens.showScreen(pin.getForm());
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
        WalletHomeScreen home = new WalletHomeScreen(screens, this);
        screens.showScreen(home.getScreen());
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
            byte[] seed = walletStore.unlock(pin);
            if (seed != null) {
                currentSeed = seed;
                currentPassphrase = walletStore.getPassphrase(pin);
                showHome();
            } else {
                PinScreen pinScreen = new PinScreen(
                        screens, PinScreen.MODE_ENTER, this);
                screens.showError("Wrong PIN", pinScreen.getForm());
            }
        } catch (Exception e) {
            PinScreen pinScreen = new PinScreen(
                    screens, PinScreen.MODE_ENTER, this);
            screens.showError("Unlock failed: " + e.getMessage(),
                    pinScreen.getForm());
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

            WalletHomeScreen home = new WalletHomeScreen(screens, this);
            screens.showInfo("Wallet created!", home.getScreen());
        } catch (CryptoError e) {
            screens.showError("Wallet creation failed: " + e.getMessage(),
                    null);
            showOnboarding();
        } catch (Exception e) {
            screens.showError("Storage error: " + e.getMessage(), null);
            showOnboarding();
        }
    }

    public void onPinCancelled() {
        if (walletStore.walletExists()) {
            screens.exit();
        } else {
            showOnboarding();
        }
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
                screens.showError("Failed to load address: " + e.getMessage(),
                        null);
                showHome();
            }
        } else if (action == WalletHomeScreen.ACTION_SIGN) {
            ManualEntryScreen entry = new ManualEntryScreen(screens, this);
            screens.showScreen(entry.getScreen());
        } else if (action == WalletHomeScreen.ACTION_SETTINGS) {
            try {
                boolean testnet = walletStore.isTestnet();
                SettingsScreen settings = new SettingsScreen(
                        screens, this, testnet);
                screens.showScreen(settings.getScreen());
            } catch (Exception e) {
                screens.showError(
                        "Failed to load settings: " + e.getMessage(), null);
                showHome();
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
            byte[] payload = address.getBytes("UTF-8");
            QrDisplayScreen qrScreen = new QrDisplayScreen(
                    screens, this, payload, "Receive Address");
            screens.showScreen(qrScreen.getScreen());
        } catch (Exception e) {
            screens.showError("Failed to show QR: " + e.getMessage(), null);
            showHome();
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
            // Wipe failed — continue anyway to clear memory
        }
        wipeSensitiveData();

        OnboardingScreen onboarding = new OnboardingScreen(screens, this);
        // Build the welcome screen first so showInfo can transition to it
        onboarding.show();
    }

    public void onSettingsBack() {
        showHome();
    }

    // ---- ManualEntryListener ----

    public void onPsbtEntered(byte[] psbt) {
        try {
            boolean testnet = walletStore.isTestnet();
            PsbtTransaction tx = PsbtParser.parse(psbt);
            TransactionReviewScreen review = new TransactionReviewScreen(
                    screens, this, tx, testnet);
            screens.showScreen(review.getScreen());
        } catch (CryptoError e) {
            screens.showError("PSBT parse error: " + e.getMessage(), null);
            showHome();
        } catch (Exception e) {
            screens.showError("Failed to parse PSBT: " + e.getMessage(), null);
            showHome();
        }
    }

    public void onManualEntryCancelled() {
        showHome();
    }

    // ---- TransactionReviewListener ----

    public void onApprove(PsbtTransaction psbt) {
        try {
            boolean testnet = walletStore.isTestnet();
            PsbtSigner.sign(psbt, currentSeed, testnet);
            byte[] signedBytes = PsbtSerializer.serialize(psbt);
            QrDisplayScreen qrScreen = new QrDisplayScreen(
                    screens, this, signedBytes, "Signed PSBT");
            screens.showScreen(qrScreen.getScreen());
        } catch (CryptoError e) {
            screens.showError("Signing failed: " + e.getMessage(), null);
            showHome();
        } catch (Exception e) {
            screens.showError("Signing error: " + e.getMessage(), null);
            showHome();
        }
    }

    public void onReject() {
        showHome();
    }

    // ---- QrDisplayListener ----

    public void onQrDisplayDone() {
        showHome();
    }

    // ---- Security ----

    /**
     * Zero-fill all sensitive in-memory data.
     */
    private void wipeSensitiveData() {
        if (currentSeed != null) {
            ByteArrayUtils.zeroFill(currentSeed);
            currentSeed = null;
        }
        currentPassphrase = null;
        pendingPin = null;
    }
}
