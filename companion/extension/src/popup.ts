/**
 * Popup script for the Burner Wallet Chrome extension companion.
 *
 * Manages wallet lifecycle: generate/import mnemonic, derive address,
 * fetch balance from Esplora. State is persisted in chrome.storage.local
 * (with localStorage fallback for dev/testing).
 */

import {
  generateMnemonic,
  validateMnemonic,
  mnemonicToSeed,
  deriveAddress,
  fetchBalance,
  type Network,
} from "./lib/crypto";

// ---------------------------------------------------------------------------
// Storage abstraction (chrome.storage.local with localStorage fallback)
// ---------------------------------------------------------------------------

interface WalletState {
  mnemonic: string;
  network: Network;
}

async function loadState(): Promise<WalletState | null> {
  try {
    if (typeof chrome !== "undefined" && chrome.storage?.local) {
      return new Promise((resolve) => {
        chrome.storage.local.get(["mnemonic", "network"], (result) => {
          if (result.mnemonic) {
            resolve({
              mnemonic: result.mnemonic,
              network: result.network || "testnet",
            });
          } else {
            resolve(null);
          }
        });
      });
    }
  } catch {
    // Fall through to localStorage
  }
  const mnemonic = localStorage.getItem("bw_mnemonic");
  if (mnemonic) {
    return {
      mnemonic,
      network: (localStorage.getItem("bw_network") as Network) || "testnet",
    };
  }
  return null;
}

async function saveState(state: WalletState): Promise<void> {
  try {
    if (typeof chrome !== "undefined" && chrome.storage?.local) {
      await chrome.storage.local.set({
        mnemonic: state.mnemonic,
        network: state.network,
      });
      return;
    }
  } catch {
    // Fall through to localStorage
  }
  localStorage.setItem("bw_mnemonic", state.mnemonic);
  localStorage.setItem("bw_network", state.network);
}

async function clearState(): Promise<void> {
  try {
    if (typeof chrome !== "undefined" && chrome.storage?.local) {
      await chrome.storage.local.remove(["mnemonic", "network"]);
      return;
    }
  } catch {
    // Fall through to localStorage
  }
  localStorage.removeItem("bw_mnemonic");
  localStorage.removeItem("bw_network");
}

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
let currentNetwork: Network = "testnet";
let mnemonicVisible = false;

function showNoWallet(): void {
  $("no-wallet").classList.remove("hidden");
  $("wallet-view").classList.add("hidden");
}

function showWalletView(): void {
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
  await saveState({ mnemonic, network });
}

// ---------------------------------------------------------------------------
// Event handlers
// ---------------------------------------------------------------------------

async function onGenerate(): Promise<void> {
  try {
    const mnemonic = generateMnemonic(12);
    await loadWallet(mnemonic, currentNetwork);
    showStatus("New wallet generated", "success");
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
  try {
    await loadWallet(input, currentNetwork);
    ($("import-input") as HTMLTextAreaElement).value = "";
    showStatus("Wallet imported", "success");
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
    await saveState({ mnemonic: currentMnemonic, network: currentNetwork });
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

async function onClear(): Promise<void> {
  if (!confirm("Clear wallet? This cannot be undone if you haven't backed up your mnemonic.")) {
    return;
  }
  currentMnemonic = null;
  await clearState();
  showNoWallet();
  showStatus("Wallet cleared", "info");
}

// ---------------------------------------------------------------------------
// Init
// ---------------------------------------------------------------------------

async function init(): Promise<void> {
  // Bind events
  $("btn-generate").addEventListener("click", onGenerate);
  $("btn-import").addEventListener("click", onImport);
  $("btn-sync").addEventListener("click", onSync);
  $("btn-clear").addEventListener("click", onClear);
  $("btn-toggle-mnemonic").addEventListener("click", onToggleMnemonic);
  $("network-select").addEventListener("change", onNetworkChange);

  // Restore persisted state
  const state = await loadState();
  if (state) {
    await loadWallet(state.mnemonic, state.network);
  } else {
    showNoWallet();
  }
}

document.addEventListener("DOMContentLoaded", init);
