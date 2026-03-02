package org.burnerwallet.ui;

import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

/**
 * Main MIDlet entry point for the Burner Wallet signer.
 *
 * This is a minimal scaffold. The real implementation will add
 * screens for onboarding, PIN entry, wallet management, transaction
 * review, and QR code display/scanning.
 */
public class BurnerWalletMIDlet extends MIDlet {

    private Display display;

    protected void startApp() throws MIDletStateChangeException {
        display = Display.getDisplay(this);

        Form form = new Form("Burner Wallet");
        form.append(new StringItem(null, "Signer v0.1.0\nScaffold ready."));
        display.setCurrent(form);
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) throws MIDletStateChangeException {
    }
}
