/**
 * Crypto abstraction layer for the Burner Wallet Chrome extension companion.
 *
 * Uses JS implementations (bip39, @scure/bip32, @noble/hashes) for now.
 * Will be swapped to the companion-core WASM bridge when available.
 */

import * as bip39 from "bip39";
import { HDKey } from "@scure/bip32";
import { bech32 } from "@scure/base";
import { sha256 } from "@noble/hashes/sha256";
import { ripemd160 } from "@noble/hashes/ripemd160";

export type Network = "mainnet" | "testnet" | "signet";

/** Generate a new BIP39 mnemonic (12 or 24 words). */
export function generateMnemonic(wordCount: 12 | 24 = 12): string {
  const strength = wordCount === 24 ? 256 : 128;
  return bip39.generateMnemonic(strength);
}

/** Validate a BIP39 mnemonic phrase. */
export function validateMnemonic(phrase: string): boolean {
  return bip39.validateMnemonic(phrase.trim());
}

/** Derive a 64-byte seed from a mnemonic and optional passphrase. */
export async function mnemonicToSeed(
  phrase: string,
  passphrase: string = ""
): Promise<Uint8Array> {
  const buf = await bip39.mnemonicToSeed(phrase.trim(), passphrase);
  return new Uint8Array(buf);
}

/** RIPEMD160(SHA256(data)) -- standard Bitcoin hash160. */
function hash160(data: Uint8Array): Uint8Array {
  return ripemd160(sha256(data));
}

/** Derive a BIP84 P2WPKH (native SegWit bech32) address from a seed. */
export function deriveAddress(
  seed: Uint8Array,
  network: Network,
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
}

/** Get the Esplora API base URL for a network. */
export function esploraUrl(network: Network): string {
  switch (network) {
    case "mainnet":
      return "https://mempool.space/api";
    case "signet":
      return "https://mempool.space/signet/api";
    default:
      return "https://mempool.space/testnet/api";
  }
}

/** Fetch total balance for an address from Esplora. */
export async function fetchBalance(
  address: string,
  network: Network
): Promise<{ confirmed: number; unconfirmed: number }> {
  const url = `${esploraUrl(network)}/address/${address}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Esplora error: ${res.status}`);
  const data = await res.json();
  return {
    confirmed:
      (data.chain_stats?.funded_txo_sum ?? 0) -
      (data.chain_stats?.spent_txo_sum ?? 0),
    unconfirmed:
      (data.mempool_stats?.funded_txo_sum ?? 0) -
      (data.mempool_stats?.spent_txo_sum ?? 0),
  };
}
