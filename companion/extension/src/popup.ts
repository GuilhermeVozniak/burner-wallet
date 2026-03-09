/**
 * Popup script for the Burner Wallet Chrome extension companion.
 *
 * Manages wallet lifecycle: generate/import mnemonic, derive address,
 * fetch balance from Esplora. The mnemonic is encrypted with a user-provided
 * password (AES-256-GCM via PBKDF2) before being stored. It is only held
 * in memory while the popup is open.
 */

import {
  generateMnemonic,
  validateMnemonic,
  mnemonicToSeed,
  deriveAddress,
  fetchBalance,
  type Network,
} from "./lib/crypto";

import {
  storeMnemonic,
  loadMnemonic,
  clearMnemonic,
  hasMnemonic,
  storeNetwork,
  loadNetwork,
} from "./lib/storage";

// ---------------------------------------------------------------------------
// DOM helpers
// ---------------------------------------------------------------------------

function $(id: string): HTMLElement {
  const el = document.getElementById(id);
  if (!el) throw new Error(`Element #${id} not found`);
  return el;
}

function showStatus(msg: string, type: "error" | "success" | "info"): void {
  const bar = $("status-bar");
  bar.textContent = msg;
  bar.className = type;
  bar.classList.remove("hidden");
  setTimeout(() => bar.classList.add("hidden"), 4000);
}

// ---------------------------------------------------------------------------
// UI state management
// ---------------------------------------------------------------------------

let currentMnemonic: string | null = null;
let currentPassword: string | null = null;
let currentNetwork: Network = "testnet";
let mnemonicVisible = false;

function showUnlockView(): void {
  $("unlock-view").classList.remove("hidden");
  $("no-wallet").classList.add("hidden");
  $("wallet-view").classList.add("hidden");
}

function showNoWallet(): void {
  $("unlock-view").classList.add("hidden");
  $("no-wallet").classList.remove("hidden");
  $("wallet-view").classList.add("hidden");
}

function showWalletView(): void {
  $("unlock-view").classList.add("hidden");
  $("no-wallet").classList.add("hidden");
  $("wallet-view").classList.remove("hidden");
}

async function deriveAndShow(): Promise<void> {
  if (!currentMnemonic) return;

  const seed = await mnemonicToSeed(currentMnemonic);
  const address = deriveAddress(seed, currentNetwork);
  $("address-display").textContent = address;

  // Reset balance display
  $("balance-confirmed").textContent = "-- sats";
  $("balance-unconfirmed").textContent = "-- unconfirmed";
}

async function loadWallet(mnemonic: string, network: Network): Promise<void> {
  currentMnemonic = mnemonic;
  currentNetwork = network;

  // Update network selector
  const sel = $("network-select") as HTMLSelectElement;
  sel.value = network;

  // Update mnemonic display
  $("mnemonic-display").textContent = mnemonic;
  mnemonicVisible = false;
  $("mnemonic-display").classList.add("hidden");
  $("btn-toggle-mnemonic").textContent = "Show";

  showWalletView();
  await deriveAndShow();
}

// ---------------------------------------------------------------------------
// Password validation
// ---------------------------------------------------------------------------

function validatePassword(password: string): string | null {
  if (password.length < 8) {
    return "Password must be at least 8 characters";
  }
  return null;
}

function getSetupPasswords(): { password: string; error: string | null } {
  const pw = ($("setup-password") as HTMLInputElement).value;
  const confirm = ($("setup-password-confirm") as HTMLInputElement).value;

  const validationError = validatePassword(pw);
  if (validationError) return { password: "", error: validationError };

  if (pw !== confirm) {
    return { password: "", error: "Passwords do not match" };
  }

  return { password: pw, error: null };
}

// ---------------------------------------------------------------------------
// Event handlers
// ---------------------------------------------------------------------------

async function onUnlock(): Promise<void> {
  const pw = ($("unlock-password") as HTMLInputElement).value;
  if (!pw) {
    showStatus("Enter your password", "error");
    return;
  }

  const btn = $("btn-unlock") as HTMLButtonElement;
  btn.disabled = true;
  btn.textContent = "Unlocking...";

  try {
    const mnemonic = await loadMnemonic(pw);
    if (!mnemonic) {
      showStatus("Wrong password", "error");
      return;
    }

    currentPassword = pw;
    const network = (await loadNetwork()) as Network;
    await loadWallet(mnemonic, network);
    showStatus("Wallet unlocked", "success");
  } catch (err) {
    showStatus(`Unlock failed: ${err}`, "error");
  } finally {
    btn.disabled = false;
    btn.textContent = "Unlock";
    ($("unlock-password") as HTMLInputElement).value = "";
  }
}

async function onGenerate(): Promise<void> {
  const { password, error } = getSetupPasswords();
  if (error) {
    showStatus(error, "error");
    return;
  }

  try {
    const mnemonic = generateMnemonic(12);
    await storeMnemonic(mnemonic, password);
    await storeNetwork(currentNetwork);
    currentPassword = password;
    await loadWallet(mnemonic, currentNetwork);
    showStatus("New wallet generated and encrypted", "success");
  } catch (err) {
    showStatus(`Generation failed: ${err}`, "error");
  }
}

async function onImport(): Promise<void> {
  const input = ($("import-input") as HTMLTextAreaElement).value.trim();
  if (!input) {
    showStatus("Enter a mnemonic phrase", "error");
    return;
  }
  if (!validateMnemonic(input)) {
    showStatus("Invalid mnemonic phrase", "error");
    return;
  }

  const { password, error } = getSetupPasswords();
  if (error) {
    showStatus(error, "error");
    return;
  }

  try {
    await storeMnemonic(input, password);
    await storeNetwork(currentNetwork);
    currentPassword = password;
    await loadWallet(input, currentNetwork);
    ($("import-input") as HTMLTextAreaElement).value = "";
    showStatus("Wallet imported and encrypted", "success");
  } catch (err) {
    showStatus(`Import failed: ${err}`, "error");
  }
}

async function onSync(): Promise<void> {
  const address = $("address-display").textContent;
  if (!address || address === "") {
    showStatus("No address to sync", "error");
    return;
  }

  const btn = $("btn-sync") as HTMLButtonElement;
  btn.disabled = true;
  btn.textContent = "...";

  try {
    showStatus("Fetching balance...", "info");
    const balance = await fetchBalance(address, currentNetwork);
    $("balance-confirmed").textContent = `${balance.confirmed.toLocaleString()} sats`;
    $("balance-unconfirmed").textContent =
      balance.unconfirmed !== 0
        ? `${balance.unconfirmed >= 0 ? "+" : ""}${balance.unconfirmed.toLocaleString()} unconfirmed`
        : "0 unconfirmed";
    showStatus("Balance synced", "success");
  } catch (err) {
    showStatus(`Sync failed: ${err}`, "error");
  } finally {
    btn.disabled = false;
    btn.textContent = "Sync";
  }
}

async function onNetworkChange(): Promise<void> {
  const sel = $("network-select") as HTMLSelectElement;
  currentNetwork = sel.value as Network;

  if (currentMnemonic) {
    await deriveAndShow();
    await storeNetwork(currentNetwork);
    showStatus(`Switched to ${currentNetwork}`, "info");
  }
}

function onToggleMnemonic(): void {
  mnemonicVisible = !mnemonicVisible;
  if (mnemonicVisible) {
    $("mnemonic-display").classList.remove("hidden");
    $("btn-toggle-mnemonic").textContent = "Hide";
  } else {
    $("mnemonic-display").classList.add("hidden");
    $("btn-toggle-mnemonic").textContent = "Show";
  }
}

function onLock(): void {
  currentMnemonic = null;
  currentPassword = null;
  showUnlockView();
  showStatus("Wallet locked", "info");
}

async function onClear(): Promise<void> {
  if (!confirm("Clear wallet? This cannot be undone if you haven't backed up your mnemonic.")) {
    return;
  }
  currentMnemonic = null;
  currentPassword = null;
  await clearMnemonic();
  showNoWallet();
  showStatus("Wallet cleared", "info");
}

// ---------------------------------------------------------------------------
// Init
// ---------------------------------------------------------------------------

async function init(): Promise<void> {
  // Bind events
  $("btn-unlock").addEventListener("click", onUnlock);
  $("btn-generate").addEventListener("click", onGenerate);
  $("btn-import").addEventListener("click", onImport);
  $("btn-sync").addEventListener("click", onSync);
  $("btn-clear").addEventListener("click", onClear);
  $("btn-lock").addEventListener("click", onLock);
  $("btn-toggle-mnemonic").addEventListener("click", onToggleMnemonic);
  $("network-select").addEventListener("change", onNetworkChange);

  // Allow Enter key to submit on password fields
  $("unlock-password").addEventListener("keydown", (e) => {
    if ((e as KeyboardEvent).key === "Enter") onUnlock();
  });

  // Determine initial view
  const stored = await hasMnemonic();
  if (stored) {
    // Encrypted mnemonic exists — show unlock screen
    currentNetwork = (await loadNetwork()) as Network;
    ($("network-select") as HTMLSelectElement).value = currentNetwork;
    showUnlockView();
  } else {
    showNoWallet();
  }
}

document.addEventListener("DOMContentLoaded", init);
