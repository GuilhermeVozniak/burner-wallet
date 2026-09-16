/**
 * Crypto abstraction layer for the Burner Wallet web companion.
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

/** BIP32 hardened-index offset. */
const HARDENED = 0x80000000;

/** Generate a new BIP39 mnemonic (12 or 24 words). */
export function generateMnemonic(wordCount: 12 | 24 = 12): string {
  const strength = wordCount === 24 ? 256 : 128;
  return bip39.generateMnemonic(strength);
}

/**
 * Normalize a user-entered mnemonic: lowercase, collapse all whitespace
 * (newlines, tabs, double spaces) to single spaces, trim.
 *
 * BIP39 seed derivation hashes the exact phrase string, so a stray newline
 * or double space would silently produce a different wallet.
 */
export function normalizeMnemonic(phrase: string): string {
  return phrase.trim().toLowerCase().split(/\s+/).join(" ");
}

/** Validate a BIP39 mnemonic phrase. */
export function validateMnemonic(phrase: string): boolean {
  return bip39.validateMnemonic(normalizeMnemonic(phrase));
}

/** Derive a 64-byte seed from a mnemonic and optional passphrase. */
export async function mnemonicToSeed(
  phrase: string,
  passphrase: string = ""
): Promise<Uint8Array> {
  const buf = await bip39.mnemonicToSeed(normalizeMnemonic(phrase), passphrase);
  return new Uint8Array(buf);
}

/** RIPEMD160(SHA256(data)) -- standard Bitcoin hash160. */
function hash160(data: Uint8Array): Uint8Array {
  return ripemd160(sha256(data));
}

/** A derived BIP84 key with everything needed to spend from or pay to it. */
export interface DerivedKey {
  /** 33-byte compressed public key. */
  publicKey: Uint8Array;
  /** P2WPKH scriptPubKey: 0x00 0x14 <hash160(pubkey)>. */
  script: Uint8Array;
  /** bech32 address for this key. */
  address: string;
  /** Master key fingerprint (BIP32), for PSBT bip32_derivation. */
  fingerprint: number;
  /** Derivation path as child numbers (hardened = index + 0x80000000). */
  path: number[];
}

/**
 * Derive a BIP84 key: m/84'/coin'/account'/change/index.
 *
 * The address encoding here is the cross-implementation-verified path
 * (see protocol/vectors/cross-impl-wallet.json); keep it byte-for-byte.
 */
export function deriveKey(
  seed: Uint8Array,
  network: Network,
  change: boolean,
  index: number,
  account: number = 0
): DerivedKey {
  const coinType = network === "mainnet" ? 0 : 1;
  const changeIdx = change ? 1 : 0;
  const path = `m/84'/${coinType}'/${account}'/${changeIdx}/${index}`;
  const master = HDKey.fromMasterSeed(seed);
  const child = master.derive(path);

  if (!child.publicKey) {
    throw new Error("Failed to derive public key");
  }

  const pubkeyHash = hash160(child.publicKey);
  const hrp = network === "mainnet" ? "bc" : "tb";
  const words = bech32.toWords(pubkeyHash);
  const address = bech32.encode(hrp, [0, ...words]);

  const script = new Uint8Array(22);
  script[0] = 0x00;
  script[1] = 0x14;
  script.set(pubkeyHash, 2);

  return {
    publicKey: child.publicKey,
    script,
    address,
    fingerprint: master.fingerprint,
    path: [84 + HARDENED, coinType + HARDENED, account + HARDENED, changeIdx, index],
  };
}

/** Derive a BIP84 P2WPKH (native SegWit bech32) receive address from a seed. */
export function deriveAddress(
  seed: Uint8Array,
  network: Network,
  account: number = 0,
  index: number = 0
): string {
  return deriveKey(seed, network, false, index, account).address;
}

/** Derive multiple receive addresses. */
export function deriveAddresses(
  seed: Uint8Array,
  network: Network,
  account: number = 0,
  count: number = 5
): string[] {
  const addresses: string[] = [];
  for (let i = 0; i < count; i++) {
    addresses.push(deriveAddress(seed, network, account, i));
  }
  return addresses;
}

/**
 * Optional self-hosted Esplora base URL, e.g. `http://127.0.0.1:3002`.
 * Set `NEXT_PUBLIC_ESPLORA_URL` at build/dev time. When present it is used for
 * every network, so it must serve the chain selected in the UI. Next.js inlines
 * `process.env.NEXT_PUBLIC_*` into the client bundle at build time.
 */
const ESPLORA_URL_OVERRIDE = process.env.NEXT_PUBLIC_ESPLORA_URL;

/**
 * Get the Esplora API base URL for a network.
 *
 * "testnet" means testnet3, matching the Rust core (`Network::Testnet`) and
 * every other companion. All companions must query the same chain.
 */
export function esploraUrl(network: Network): string {
  if (ESPLORA_URL_OVERRIDE) return ESPLORA_URL_OVERRIDE.replace(/\/+$/, "");
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

/** UTXO from Esplora API. */
export interface Utxo {
  txid: string;
  vout: number;
  value: number;
  status: { confirmed: boolean; block_height?: number };
}

/** Fetch UTXOs for an address from Esplora. */
export async function fetchUtxos(
  address: string,
  network: Network
): Promise<Utxo[]> {
  const url = `${esploraUrl(network)}/address/${address}/utxo`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Esplora UTXO error: ${res.status}`);
  return await res.json();
}

/**
 * Fetch the raw serialized hex of a transaction from Esplora.
 *
 * Needed for the PSBT `non_witness_utxo` field: the signer verifies each
 * input's amount against the actual previous transaction instead of
 * trusting the amount the companion claims.
 */
export async function fetchTxHex(txid: string, network: Network): Promise<string> {
  const url = `${esploraUrl(network)}/tx/${txid}/hex`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Esplora tx error: ${res.status}`);
  const text = (await res.text()).trim();
  if (!/^[0-9a-fA-F]+$/.test(text) || text.length % 2 !== 0) {
    throw new Error(`Esplora returned malformed transaction hex for ${txid}`);
  }
  return text;
}

/** Transaction summary from Esplora. */
export interface TxInfo {
  txid: string;
  status: { confirmed: boolean; block_height?: number };
  fee: number;
}

/** Fetch recent transactions for an address from Esplora. */
export async function fetchTransactions(
  address: string,
  network: Network
): Promise<TxInfo[]> {
  const url = `${esploraUrl(network)}/address/${address}/txs`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Esplora txs error: ${res.status}`);
  const txs = await res.json();
  return txs.map((tx: Record<string, unknown>) => ({
    txid: tx.txid as string,
    status: tx.status as { confirmed: boolean; block_height?: number },
    fee: (tx.fee as number) ?? 0,
  }));
}

/** Select UTXOs to cover the target amount + estimated fee. Returns selected UTXOs and total. */
export function selectUtxos(
  utxos: Utxo[],
  targetSats: number,
  feeRateSatVb: number
): { selected: Utxo[]; total: number; estimatedFee: number } {
  // Sort by value descending for simple largest-first selection
  const sorted = [...utxos].sort((a, b) => b.value - a.value);

  const selected: Utxo[] = [];
  let total = 0;

  // Estimate tx size: ~10 overhead + 68 per input + 31 per output (2 outputs: recipient + change)
  for (const utxo of sorted) {
    selected.push(utxo);
    total += utxo.value;

    const estimatedVbytes = 10 + selected.length * 68 + 2 * 31;
    const estimatedFee = Math.ceil(estimatedVbytes * feeRateSatVb);

    if (total >= targetSats + estimatedFee) {
      return { selected, total, estimatedFee };
    }
  }

  // Not enough funds
  const estimatedVbytes = 10 + selected.length * 68 + 2 * 31;
  const estimatedFee = Math.ceil(estimatedVbytes * feeRateSatVb);
  return { selected, total, estimatedFee };
}

/** Build a human-readable send summary for the signer. */
export function buildSendSummary(params: {
  recipient: string;
  amountSats: number;
  feeRateSatVb: number;
  changeAddress: string;
  utxos: Utxo[];
  network: Network;
}): {
  inputs: { txid: string; vout: number; value: number }[];
  outputs: { address: string; value: number }[];
  fee: number;
  change: number;
  error?: string;
} {
  const { selected, total, estimatedFee } = selectUtxos(
    params.utxos,
    params.amountSats,
    params.feeRateSatVb
  );

  if (total < params.amountSats + estimatedFee) {
    return {
      inputs: [],
      outputs: [],
      fee: estimatedFee,
      change: 0,
      error: `Insufficient funds: have ${total} sats, need ${params.amountSats + estimatedFee} sats (${params.amountSats} + ${estimatedFee} fee)`,
    };
  }

  const change = total - params.amountSats - estimatedFee;
  const outputs: { address: string; value: number }[] = [
    { address: params.recipient, value: params.amountSats },
  ];

  // Only add change output if above dust threshold (546 sats)
  if (change > 546) {
    outputs.push({ address: params.changeAddress, value: change });
  }

  return {
    inputs: selected.map((u) => ({ txid: u.txid, vout: u.vout, value: u.value })),
    outputs,
    fee: estimatedFee + (change <= 546 ? change : 0),
    change: change > 546 ? change : 0,
  };
}

/** Derive a change address (BIP84 internal chain). */
export function deriveChangeAddress(
  seed: Uint8Array,
  network: Network,
  account: number = 0,
  index: number = 0
): string {
  return deriveKey(seed, network, true, index, account).address;
}

/** Broadcast a raw transaction hex via Esplora. Returns txid. */
export async function broadcastTx(
  txHex: string,
  network: Network
): Promise<string> {
  const url = `${esploraUrl(network)}/tx`;
  const res = await fetch(url, {
    method: "POST",
    body: txHex,
    headers: { "Content-Type": "text/plain" },
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Broadcast failed: ${body}`);
  }
  return await res.text();
}
