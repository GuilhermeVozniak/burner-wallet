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

/** Generate a new BIP39 mnemonic (12 or 24 words). */
export function generateMnemonic(wordCount: 12 | 24 = 12): string {
  const strength = wordCount === 24 ? 256 : 128;
  return bip39.generateMnemonic(strength);
}

/** Validate a BIP39 mnemonic phrase. */
export function validateMnemonic(phrase: string): boolean {
  return bip39.validateMnemonic(phrase);
}

/** Derive a 64-byte seed from a mnemonic and optional passphrase. */
export async function mnemonicToSeed(
  phrase: string,
  passphrase: string = ""
): Promise<Uint8Array> {
  const buf = await bip39.mnemonicToSeed(phrase, passphrase);
  return new Uint8Array(buf);
}

/** RIPEMD160(SHA256(data)) -- standard Bitcoin hash160. */
function hash160(data: Uint8Array): Uint8Array {
  return ripemd160(sha256(data));
}

/** Derive a BIP84 P2WPKH (native SegWit bech32) address from a seed. */
export function deriveAddress(
  seed: Uint8Array,
  network: "mainnet" | "testnet" | "signet",
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

/** Derive multiple receive addresses. */
export function deriveAddresses(
  seed: Uint8Array,
  network: "mainnet" | "testnet" | "signet",
  account: number = 0,
  count: number = 5
): string[] {
  const addresses: string[] = [];
  for (let i = 0; i < count; i++) {
    addresses.push(deriveAddress(seed, network, account, i));
  }
  return addresses;
}

/** Get the Esplora API base URL for a network. */
export function esploraUrl(network: "mainnet" | "testnet" | "signet"): string {
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
  network: "mainnet" | "testnet" | "signet"
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
  network: "mainnet" | "testnet" | "signet"
): Promise<Utxo[]> {
  const url = `${esploraUrl(network)}/address/${address}/utxo`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Esplora UTXO error: ${res.status}`);
  return await res.json();
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
  network: "mainnet" | "testnet" | "signet"
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
  network: "mainnet" | "testnet" | "signet";
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
  network: "mainnet" | "testnet" | "signet",
  account: number = 0,
  index: number = 0
): string {
  const coinType = network === "mainnet" ? 0 : 1;
  const path = `m/84'/${coinType}'/${account}'/1/${index}`;
  const master = HDKey.fromMasterSeed(seed);
  const child = master.derive(path);
  if (!child.publicKey) throw new Error("Failed to derive change key");
  const pubkeyHash = hash160(child.publicKey);
  const hrp = network === "mainnet" ? "bc" : "tb";
  const words = bech32.toWords(pubkeyHash);
  return bech32.encode(hrp, [0, ...words]);
}

/** Broadcast a raw transaction hex via Esplora. Returns txid. */
export async function broadcastTx(
  txHex: string,
  network: "mainnet" | "testnet" | "signet"
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
