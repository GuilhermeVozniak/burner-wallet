/**
 * Encrypted storage for mnemonic phrases.
 *
 * Uses Web Crypto API (SubtleCrypto) to encrypt the mnemonic with a
 * user-provided password before persisting to chrome.storage.local.
 * The password is never stored — only the encrypted ciphertext.
 *
 * Encryption: AES-256-GCM with PBKDF2 key derivation (100k iterations).
 * Wire format: base64(salt[16] || iv[12] || ciphertext[N])
 */

/** Derive an AES-256-GCM key from a password using PBKDF2. */
async function deriveKey(password: string, salt: Uint8Array): Promise<CryptoKey> {
  const enc = new TextEncoder();
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    enc.encode(password),
    "PBKDF2",
    false,
    ["deriveKey"]
  );
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: salt as BufferSource, iterations: 100_000, hash: "SHA-256" },
    keyMaterial,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
}

/** Encrypt a string with a password. Returns base64-encoded ciphertext. */
export async function encrypt(plaintext: string, password: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(password, salt);
  const enc = new TextEncoder();
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: iv as BufferSource },
    key,
    enc.encode(plaintext) as BufferSource
  );
  // Pack: salt(16) + iv(12) + ciphertext
  const packed = new Uint8Array(16 + 12 + new Uint8Array(ciphertext).length);
  packed.set(salt, 0);
  packed.set(iv, 16);
  packed.set(new Uint8Array(ciphertext), 28);
  return btoa(String.fromCharCode(...packed));
}

/** Decrypt a base64-encoded ciphertext with a password. */
export async function decrypt(encoded: string, password: string): Promise<string> {
  const packed = Uint8Array.from(atob(encoded), (c) => c.charCodeAt(0));
  const salt = packed.slice(0, 16);
  const iv = packed.slice(16, 28);
  const ciphertext = packed.slice(28);
  const key = await deriveKey(password, salt);
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: iv as BufferSource },
    key,
    ciphertext as BufferSource
  );
  return new TextDecoder().decode(plaintext);
}

/** Store encrypted mnemonic. */
export async function storeMnemonic(mnemonic: string, password: string): Promise<void> {
  const encrypted = await encrypt(mnemonic, password);
  try {
    if (typeof chrome !== "undefined" && chrome.storage?.local) {
      await chrome.storage.local.set({ bw_mnemonic_enc: encrypted });
      return;
    }
  } catch {
    // Fall through to localStorage
  }
  localStorage.setItem("bw_mnemonic_enc", encrypted);
}

/** Store the selected network (unencrypted, not sensitive). */
export async function storeNetwork(network: string): Promise<void> {
  try {
    if (typeof chrome !== "undefined" && chrome.storage?.local) {
      await chrome.storage.local.set({ bw_network: network });
      return;
    }
  } catch {
    // Fall through to localStorage
  }
  localStorage.setItem("bw_network", network);
}

/** Load the selected network. */
export async function loadNetwork(): Promise<string> {
  try {
    if (typeof chrome !== "undefined" && chrome.storage?.local) {
      const result = await chrome.storage.local.get("bw_network");
      return result.bw_network || "testnet";
    }
  } catch {
    // Fall through to localStorage
  }
  return localStorage.getItem("bw_network") || "testnet";
}

/** Load and decrypt mnemonic. Returns null if not found or wrong password. */
export async function loadMnemonic(password: string): Promise<string | null> {
  let encrypted: string | null = null;
  try {
    if (typeof chrome !== "undefined" && chrome.storage?.local) {
      const result = await chrome.storage.local.get("bw_mnemonic_enc");
      encrypted = result.bw_mnemonic_enc || null;
    }
  } catch {
    // Fall through to localStorage
  }
  if (encrypted === null) {
    encrypted = localStorage.getItem("bw_mnemonic_enc");
  }
  if (!encrypted) return null;
  try {
    return await decrypt(encrypted, password);
  } catch {
    return null; // Wrong password or corrupted data
  }
}

/** Clear stored mnemonic and network. */
export async function clearMnemonic(): Promise<void> {
  try {
    if (typeof chrome !== "undefined" && chrome.storage?.local) {
      await chrome.storage.local.remove(["bw_mnemonic_enc", "bw_network"]);
      return;
    }
  } catch {
    // Fall through to localStorage
  }
  localStorage.removeItem("bw_mnemonic_enc");
  localStorage.removeItem("bw_network");
}

/** Check if an encrypted mnemonic is stored (does not require password). */
export async function hasMnemonic(): Promise<boolean> {
  try {
    if (typeof chrome !== "undefined" && chrome.storage?.local) {
      const result = await chrome.storage.local.get("bw_mnemonic_enc");
      return !!result.bw_mnemonic_enc;
    }
  } catch {
    // Fall through to localStorage
  }
  return !!localStorage.getItem("bw_mnemonic_enc");
}
