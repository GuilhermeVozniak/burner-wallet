import { contextBridge } from "electron";
import * as bip39 from "bip39";
import { HDKey } from "@scure/bip32";
import { bech32 } from "@scure/base";
import { sha256 } from "@noble/hashes/sha256";
import { ripemd160 } from "@noble/hashes/ripemd160";

/** RIPEMD160(SHA256(data)) -- standard Bitcoin hash160. */
function hash160(data: Uint8Array): Uint8Array {
  return ripemd160(sha256(data));
}

/** Get the Esplora API base URL for a network. */
function esploraUrl(network: string): string {
  switch (network) {
    case "mainnet":
      return "https://mempool.space/api";
    case "signet":
      return "https://mempool.space/signet/api";
    default:
      return "https://mempool.space/testnet4/api";
  }
}

// Expose a secure API to the renderer process via contextBridge.
// All crypto operations happen here in the preload (Node.js context),
// using the same battle-tested npm libraries as the web and extension apps.
contextBridge.exposeInMainWorld("burnerAPI", {
  platform: process.platform,
  electronVersion: process.versions.electron,

  /** Generate a new BIP39 mnemonic (12 or 24 words). */
  generateMnemonic(wordCount: number): string {
    const strength = wordCount === 24 ? 256 : 128;
    return bip39.generateMnemonic(strength);
  },

  /** Validate a BIP39 mnemonic phrase. */
  validateMnemonic(phrase: string): boolean {
    return bip39.validateMnemonic(phrase);
  },

  /** Derive a 64-byte seed from a mnemonic and optional passphrase. */
  async mnemonicToSeed(
    phrase: string,
    passphrase: string = ""
  ): Promise<Uint8Array> {
    const buf = await bip39.mnemonicToSeed(phrase, passphrase);
    return new Uint8Array(buf);
  },

  /** Derive a BIP84 P2WPKH (native SegWit bech32) address from a seed. */
  deriveAddress(
    seed: Uint8Array,
    network: string,
    account: number = 0,
    index: number = 0
  ): string {
    const coinType = network === "mainnet" ? 0 : 1;
    const path = `m/84'/${coinType}'/${account}'/0/${index}`;
    const master = HDKey.fromMasterSeed(seed);
    const child = master.derive(path);

    if (!child.publicKey) {
      throw new Error("Failed to derive public key");
    }

    const pubkeyHash = hash160(child.publicKey);
    const hrp = network === "mainnet" ? "bc" : "tb";
    const words = bech32.toWords(pubkeyHash);
    return bech32.encode(hrp, [0, ...words]);
  },

  /** Fetch balance for an address from Esplora. */
  async fetchBalance(
    address: string,
    network: string
  ): Promise<{ confirmed: number; unconfirmed: number }> {
    const base = esploraUrl(network);
    const res = await fetch(`${base}/address/${address}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    const confirmed =
      (data.chain_stats?.funded_txo_sum ?? 0) -
      (data.chain_stats?.spent_txo_sum ?? 0);
    const unconfirmed =
      (data.mempool_stats?.funded_txo_sum ?? 0) -
      (data.mempool_stats?.spent_txo_sum ?? 0);
    return { confirmed, unconfirmed };
  },
});
