/**
 * PSBT (BIP174) construction, parsing, and finalization for the web companion,
 * plus the multi-frame QR framing shared with the Java ME signer.
 *
 * The signer (signer/src/org/burnerwallet/chains/bitcoin/PsbtSigner.java) expects
 * a binary PSBT v0 whose inputs carry `witness_utxo` (it locates the signing key
 * by scanning BIP84 paths for a matching P2WPKH script). It returns the same PSBT
 * with `partial_sig` entries added; it does NOT finalize. This module builds
 * that unsigned PSBT and turns the signed PSBT back into a broadcastable
 * transaction, verifying the signatures in the process.
 *
 * Transport framing mirrors signer/src/org/burnerwallet/transport/MultiFrameEncoder.java:
 *   [total_frames (1 byte)] [frame_index (1 byte)] [payload_chunk (N bytes)]
 */

import { hex } from "@scure/base";
import { secp256k1 } from "@noble/curves/secp256k1.js";
import { Address, NETWORK, OutScript, TEST_NETWORK, Transaction, p2wpkh } from "@scure/btc-signer";
import type { DerivedKey, Network } from "./crypto";

/** Must match QrDisplayScreen.MAX_BYTES_PER_FRAME on the signer. */
export const MAX_BYTES_PER_FRAME = 150;

/** Multi-frame header: [total][index]. */
const FRAME_HEADER_SIZE = 2;

/** Single-byte frame counters cap the payload at 255 frames. */
const MAX_FRAMES = 255;

/** nSequence signalling opt-in RBF (BIP125), same as BDK's default. */
export const RBF_SEQUENCE = 0xfffffffd;

/** BIP174 magic prefix: "psbt" 0xff. */
export const PSBT_MAGIC_HEX = "70736274ff";

/** The only sighash type the signer produces and this companion accepts. */
export const SIGHASH_ALL = 0x01;

/** Minimum length of a DER ECDSA signature plus the trailing sighash byte. */
const MIN_SIG_WITH_TYPE_LEN = 9;

function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

/** True if `script` is a native SegWit v0 P2WPKH output script (OP_0 <20 bytes>). */
function isP2wpkhScript(script: Uint8Array): boolean {
  return script.length === 22 && script[0] === 0x00 && script[1] === 0x14;
}

type BtcNetwork = typeof NETWORK;

/** Map the companion network name to btc-signer address parameters. */
export function btcNetwork(network: Network): BtcNetwork {
  return network === "mainnet" ? NETWORK : TEST_NETWORK;
}

/**
 * Validate a recipient address for the selected network.
 *
 * Returns null when valid, otherwise a user-facing error message. Only native
 * SegWit v0 P2WPKH recipients are accepted because the signer's review screen
 * can only render that script type (TransactionReviewScreen.addressFromScript).
 */
export function validateRecipient(address: string, network: Network): string | null {
  let decoded;
  try {
    decoded = Address(btcNetwork(network)).decode(address);
  } catch {
    const expected = network === "mainnet" ? "bc1q..." : "tb1q...";
    return `Invalid ${network} address (bad checksum or wrong network). Expected ${expected}`;
  }
  if (decoded.type !== "wpkh") {
    return "Only native SegWit (bc1q/tb1q) recipients are supported by the signer";
  }
  return null;
}

export interface PsbtInputSpec {
  /** Previous txid (display byte order, as returned by Esplora). */
  txid: string;
  vout: number;
  /** Value in satoshis. */
  value: number;
  /**
   * Raw hex of the previous transaction (`non_witness_utxo`). The signer
   * refuses inputs without it: it recomputes the txid and checks the amount
   * and script against `witness_utxo` before signing.
   */
  prevTxHex: string;
}

export interface PsbtOutputSpec {
  address: string;
  /** Value in satoshis. */
  value: number;
}

export interface UnsignedPsbt {
  /** Binary BIP174 PSBT v0. */
  psbt: Uint8Array;
  /** Hex encoding of `psbt` (for manual entry on the signer). */
  psbtHex: string;
  /** Txid of the unsigned transaction (stable across signing). */
  txid: string;
  /** Absolute fee in satoshis (sum(inputs) - sum(outputs)). */
  fee: bigint;
}

/**
 * Build an unsigned PSBT spending P2WPKH UTXOs that all belong to `inputKey`.
 *
 * Every input carries `witness_utxo` and `non_witness_utxo` (both required
 * by the signer, which cross-checks them) and `bip32_derivation` (so other
 * BIP174 wallets can also sign or inspect it). btc-signer rejects a
 * `non_witness_utxo` whose txid does not match the input.
 */
export function buildUnsignedPsbt(params: {
  inputs: PsbtInputSpec[];
  inputKey: DerivedKey;
  outputs: PsbtOutputSpec[];
  network: Network;
}): UnsignedPsbt {
  if (params.inputs.length === 0) throw new Error("No inputs selected");
  if (params.outputs.length === 0) throw new Error("No outputs");

  const net = btcNetwork(params.network);
  const tx = new Transaction();

  for (const inp of params.inputs) {
    if (!/^[0-9a-fA-F]{64}$/.test(inp.txid)) {
      throw new Error(`Invalid input txid: ${inp.txid}`);
    }
    if (!Number.isSafeInteger(inp.value) || inp.value <= 0) {
      throw new Error(`Invalid input value: ${inp.value}`);
    }
    if (!/^[0-9a-fA-F]+$/.test(inp.prevTxHex) || inp.prevTxHex.length % 2 !== 0) {
      throw new Error(`Invalid previous transaction hex for input ${inp.txid}`);
    }
    tx.addInput({
      txid: hex.decode(inp.txid),
      index: inp.vout,
      sequence: RBF_SEQUENCE,
      nonWitnessUtxo: hex.decode(inp.prevTxHex),
      witnessUtxo: { script: params.inputKey.script, amount: BigInt(inp.value) },
      bip32Derivation: [
        [
          params.inputKey.publicKey,
          { fingerprint: params.inputKey.fingerprint, path: params.inputKey.path },
        ],
      ],
    });
  }

  for (const out of params.outputs) {
    if (!Number.isSafeInteger(out.value) || out.value <= 0) {
      throw new Error(`Invalid output value: ${out.value}`);
    }
    // addOutputAddress validates checksum + network and rejects unknown scripts.
    tx.addOutputAddress(out.address, BigInt(out.value), net);
  }

  const psbt = tx.toPSBT();
  return { psbt, psbtHex: hex.encode(psbt), txid: tx.id, fee: tx.fee };
}

export interface ParsedPsbtInput {
  txid: string;
  vout: number;
  /** Value from witness_utxo, or null if the PSBT does not carry it. */
  value: bigint | null;
  /** True if a partial signature or final witness is present. */
  signed: boolean;
}

export interface ParsedPsbt {
  txid: string;
  inputs: ParsedPsbtInput[];
  outputs: { address: string; value: bigint }[];
  totalOut: bigint;
  /** Absolute fee, or null if any input lacks witness_utxo. */
  fee: bigint | null;
  signedInputs: number;
}

/** Decode PSBT hex, tolerating whitespace and case. Throws on invalid input. */
export function decodePsbtHex(input: string): Uint8Array {
  const cleaned = input.replace(/\s+/g, "").toLowerCase();
  if (cleaned.length === 0) throw new Error("PSBT hex is empty");
  if (!/^[0-9a-f]+$/.test(cleaned)) throw new Error("Invalid hex string");
  if (cleaned.length % 2 !== 0) throw new Error("Hex must have even length");
  if (!cleaned.startsWith(PSBT_MAGIC_HEX)) {
    throw new Error("Not a PSBT (missing 70736274ff magic prefix)");
  }
  return hex.decode(cleaned);
}

/** True if the bytes start with the BIP174 magic prefix. */
export function hasPsbtMagic(bytes: Uint8Array): boolean {
  return bytes.length >= 5 && hex.encode(bytes.subarray(0, 5)) === PSBT_MAGIC_HEX;
}

/**
 * Parse a (signed) PSBT and summarize it for display.
 *
 * Returns the parsed transaction (needed later for finalization) together
 * with a plain summary. Throws on malformed input.
 */
export function parseSignedPsbt(
  bytes: Uint8Array,
  network: Network
): { tx: Transaction; info: ParsedPsbt } {
  if (!hasPsbtMagic(bytes)) throw new Error("Not a PSBT (missing magic prefix)");
  const net = btcNetwork(network);
  const tx = Transaction.fromPSBT(bytes);

  const inputs: ParsedPsbtInput[] = [];
  let signedInputs = 0;
  for (let i = 0; i < tx.inputsLength; i++) {
    const inp = tx.getInput(i);
    const signed =
      (inp.partialSig !== undefined && inp.partialSig.length > 0) ||
      inp.finalScriptWitness !== undefined;
    if (signed) signedInputs++;
    inputs.push({
      txid: inp.txid ? hex.encode(inp.txid) : "?",
      vout: inp.index ?? 0,
      value: inp.witnessUtxo ? inp.witnessUtxo.amount : null,
      signed,
    });
  }

  const outputs: { address: string; value: bigint }[] = [];
  let totalOut = 0n;
  for (let i = 0; i < tx.outputsLength; i++) {
    const out = tx.getOutput(i);
    const value = out.amount ?? 0n;
    totalOut += value;
    let address = tx.getOutputAddress(i, net);
    if (!address) address = `script:${out.script ? hex.encode(out.script) : "?"}`;
    outputs.push({ address, value });
  }

  let fee: bigint | null = null;
  try {
    fee = tx.fee;
  } catch {
    fee = null;
  }

  return {
    tx,
    info: { txid: tx.id, inputs, outputs, totalOut, fee, signedInputs },
  };
}

/**
 * Verify every partial signature in a signed PSBT.
 *
 * btc-signer's `finalize()` only copies the first `partial_sig` of each input
 * into the witness; it does not check it. This function does the check so a
 * signer bug, a corrupted QR frame, or a substituted signature is rejected
 * here rather than turned into a transaction the network would refuse.
 *
 * Per input it requires: a P2WPKH `witness_utxo`; exactly one `partial_sig`
 * whose public key hashes to that witness program; a SIGHASH_ALL type byte;
 * and a low-S DER signature that verifies against the BIP143 digest.
 *
 * Throws with a per-input message on the first failure.
 */
export function verifyPartialSignatures(tx: Transaction): void {
  if (tx.inputsLength === 0) throw new Error("PSBT has no inputs");
  for (let i = 0; i < tx.inputsLength; i++) {
    const inp = tx.getInput(i);
    const utxo = inp.witnessUtxo;
    if (!utxo || !utxo.script) throw new Error(`Input ${i}: missing witness_utxo`);
    const script = utxo.script;
    if (!isP2wpkhScript(script)) {
      throw new Error(`Input ${i}: only P2WPKH inputs are supported`);
    }
    if (inp.finalScriptWitness || inp.finalScriptSig) {
      throw new Error(`Input ${i}: already finalized, signature cannot be verified`);
    }
    const sigs = inp.partialSig ?? [];
    if (sigs.length === 0) throw new Error(`Input ${i}: not signed`);
    if (sigs.length > 1) throw new Error(`Input ${i}: unexpected extra signatures`);
    const [pubkey, sigWithType] = sigs[0];
    if (pubkey.length !== 33) throw new Error(`Input ${i}: public key must be compressed`);
    if (!bytesEqual(p2wpkh(pubkey).script, script)) {
      throw new Error(`Input ${i}: signature public key does not match the spent output`);
    }
    if (
      sigWithType.length < MIN_SIG_WITH_TYPE_LEN ||
      sigWithType[sigWithType.length - 1] !== SIGHASH_ALL
    ) {
      throw new Error(`Input ${i}: signature is not SIGHASH_ALL`);
    }
    // BIP143: the scriptCode of a P2WPKH input is the equivalent P2PKH script.
    const scriptCode = OutScript.encode({ type: "pkh", hash: script.subarray(2) });
    const digest = tx.preimageWitnessV0(i, scriptCode, SIGHASH_ALL, utxo.amount);
    const der = sigWithType.subarray(0, sigWithType.length - 1);
    let valid = false;
    try {
      valid = secp256k1.verify(der, digest, pubkey, { prehash: false, format: "der", lowS: true });
    } catch {
      valid = false;
    }
    if (!valid) throw new Error(`Input ${i}: invalid signature`);
  }
}

/**
 * Verify all signatures, finalize the PSBT, and return the broadcastable raw
 * transaction hex. Throws if any input is unsigned or carries a bad signature.
 */
export function finalizeToTxHex(tx: Transaction): string {
  verifyPartialSignatures(tx);
  tx.finalize();
  return hex.encode(tx.extract());
}

// ---------------------------------------------------------------------------
// Multi-frame QR framing (mirrors MultiFrameEncoder / MultiFrameDecoder)
// ---------------------------------------------------------------------------

/** Split a payload into [total][index][chunk] frames. */
export function encodeFrames(
  payload: Uint8Array,
  maxBytesPerFrame: number = MAX_BYTES_PER_FRAME
): Uint8Array[] {
  const dataPerFrame = Math.max(1, maxBytesPerFrame - FRAME_HEADER_SIZE);
  const total = Math.max(1, Math.ceil(payload.length / dataPerFrame));
  if (total > MAX_FRAMES) {
    throw new Error(
      `Payload too large for multi-frame QR (${total} frames, max ${MAX_FRAMES})`
    );
  }
  const frames: Uint8Array[] = [];
  for (let i = 0; i < total; i++) {
    const offset = i * dataPerFrame;
    const chunk = payload.subarray(offset, Math.min(payload.length, offset + dataPerFrame));
    const frame = new Uint8Array(FRAME_HEADER_SIZE + chunk.length);
    frame[0] = total;
    frame[1] = i;
    frame.set(chunk, FRAME_HEADER_SIZE);
    frames.push(frame);
  }
  return frames;
}

/** Header sanity check shared by the assembler and the text decoder. */
function frameHeaderOk(frame: Uint8Array, knownTotal: number): boolean {
  if (frame.length < FRAME_HEADER_SIZE) return false;
  const total = frame[0];
  const index = frame[1];
  if (total === 0 || index >= total) return false;
  return knownTotal <= 0 || total === knownTotal;
}

/**
 * Reassemble a payload from frames that may arrive in any order.
 * Duplicates and frames with a mismatched total are ignored.
 */
export class MultiFrameAssembler {
  private total = -1;
  private frames: (Uint8Array | null)[] = [];
  private count = 0;

  get totalFrames(): number {
    return this.total;
  }

  get receivedCount(): number {
    return this.count;
  }

  isComplete(): boolean {
    return this.total > 0 && this.count === this.total;
  }

  reset(): void {
    this.total = -1;
    this.frames = [];
    this.count = 0;
  }

  /** Feed a raw frame. Returns true if it was new and accepted. */
  addFrame(frame: Uint8Array): boolean {
    if (!frameHeaderOk(frame, this.total)) return false;
    const total = frame[0];
    const index = frame[1];
    if (this.total === -1) {
      this.total = total;
      this.frames = new Array<Uint8Array | null>(total).fill(null);
    }
    if (this.frames[index] !== null) return false;
    this.frames[index] = frame.slice(FRAME_HEADER_SIZE);
    this.count++;
    return true;
  }

  /** Concatenate all chunks. Only valid once isComplete() is true. */
  assemble(): Uint8Array {
    if (!this.isComplete()) throw new Error("Multi-frame payload incomplete");
    let len = 0;
    for (const f of this.frames) len += f!.length;
    const out = new Uint8Array(len);
    let pos = 0;
    for (const f of this.frames) {
      out.set(f!, pos);
      pos += f!.length;
    }
    return out;
  }
}

/**
 * Recover raw frame bytes from the text a browser QR scanner returned.
 *
 * The signer encodes frames in QR byte mode without an ECI header. Browser
 * scanners (html5-qrcode / zxing) only expose decoded *text*, guessing the
 * charset: usually ISO-8859-1 for binary data (one char per byte), but UTF-8
 * when the bytes happen to form valid UTF-8. Both interpretations are tried
 * and the first one with a sane frame header (consistent with `knownTotal`)
 * wins. Returns null if neither interpretation yields a valid frame.
 */
export function qrTextToFrame(text: string, knownTotal: number): Uint8Array | null {
  let latin1: Uint8Array | null = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if (code > 0xff) {
      latin1 = null;
      break;
    }
    latin1[i] = code;
  }
  if (latin1 && frameHeaderOk(latin1, knownTotal)) return latin1;

  const utf8 = new TextEncoder().encode(text);
  if (frameHeaderOk(utf8, knownTotal)) return utf8;

  return null;
}

/** Format a satoshi amount with thousands separators. */
export function formatSats(value: bigint | number): string {
  return value.toLocaleString("en-US");
}
