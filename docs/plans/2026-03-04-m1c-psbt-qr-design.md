# M1c Design: PSBT Signing & QR Transport

**Date:** 2026-03-04
**Status:** Approved
**Scope:** Full signer + companion — PSBT parsing/signing, QR display/scan, companion PSBT construction + TUI

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Scope | Both PSBT + QR in one milestone | Tightly coupled — signing useless without transport |
| QR input (signer) | Camera scan + manual hex fallback | Camera via MMAPI, manual entry as guaranteed fallback |
| Multi-frame QR | Custom sequential frames | Frame format: `[total(1)][index(1)][data(N)]`, ~150 bytes/frame |
| Companion scope | Full signer + companion | Both PSBT construction (Rust) and TUI workflow |
| Sighash types | All standard types | SIGHASH_ALL, NONE, SINGLE + ANYONECANPAY variants |
| TX review UI | Essential info + warnings | Amount, destination, fee, plus warnings for high fee / multiple recipients |
| PSBT parser | Streaming (state machine) | Low memory, fits CLDC 1.1 heap constraints |
| QR encoder | Port Nayuki library | ~1000 lines, MIT, battle-tested, no dependencies |
| Companion PSBT | rust-bitcoin + BDK | Already dependencies, standard tooling |
| TUI scope | Full (wallet, send, receive, history) | Complete end-to-end experience |

## Architecture

### Signer (Java ME)

#### PSBT Parsing & Signing — `chains/bitcoin/`

**`CompactSize`** — Bitcoin varint encoding/decoding.
- `readCompactSize(byte[] data, int offset)` → value + bytes consumed
- `writeCompactSize(long value)` → `byte[]`

**`PsbtParser`** — Streaming BIP174 v0 parser.
- Validates magic bytes (`0x70736274FF`)
- Single-pass state machine: global map → input maps → output maps
- Extracts: unsigned tx, witness UTXOs, BIP32 derivation paths, sighash types
- Ignores unknown key types (forward-compatible)
- Produces `PsbtTransaction` value object

**`PsbtTransaction`** — Parsed PSBT data.
- `byte[] unsignedTx` — raw unsigned transaction
- `PsbtInput[] inputs` — witness UTXO, sighash type, BIP32 derivation, partial sigs
- `PsbtOutput[] outputs` — amount, scriptPubKey, BIP32 derivation
- Helpers: `getTotalInputValue()`, `getTotalOutputValue()`, `getFee()`, `getDestinations()`, `getChangeOutputs(byte[] ourPubKey)`

**`PsbtInput` / `PsbtOutput`** — Per-input/output data holders.

**`TxSerializer`** — Raw transaction serialization/deserialization.
- Parse: version, inputs (prevout, scriptSig, sequence), outputs (value, scriptPubKey), locktime
- Serialize: witness transaction format for broadcast

**`Bip143Sighash`** — BIP143 segwit sighash computation.
- Implements segwit signature hash algorithm (double SHA-256)
- Caches hashPrevouts, hashSequence, hashOutputs across inputs
- Supports all sighash types

**`PsbtSigner`** — Signs parsed PSBTs.
- Derives signing key per input via BIP32 path
- Computes BIP143 sighash per sighash type
- Signs with ECDSA (RFC 6979 deterministic nonce)
- Low-S normalization (BIP 62/146)
- Adds `PARTIAL_SIG` entries to input maps

**`PsbtSerializer`** — Serializes signed PSBT back to bytes.
- Reconstructs BIP174 binary from `PsbtTransaction` with added signatures

**`Secp256k1` modifications** — Add ECDSA signing.
- `sign(byte[] messageHash, byte[] privateKey)` → `byte[64]` (r || s)
- Uses BC `ECDSASigner` + `RFC6979KCalculator`
- `serializeDER(byte[] rs)` → DER-encoded signature
- Low-S normalization: if `s > n/2`, replace with `n - s`

#### QR Transport — `transport/`

**`QrEncoder`** — QR code generation (Nayuki port to Java 1.4).
- `QrCode.encodeBinary(byte[] data, Ecc ecc)` → `QrCode`
- `getModule(int x, int y)` → boolean
- `getSize()` → int
- Supports versions 1–20, ECC levels LOW/MEDIUM/QUARTILE/HIGH
- Java 1.4: no generics, no autoboxing, no enhanced for-loops

**`QrDecoder`** — Lightweight QR code reader.
- Input: grayscale pixel array from camera snapshot
- Find finder patterns → determine version → read format → unmask → decode data
- Targets byte-mode QR codes only
- Highest-risk component — may be too slow on Nokia hardware

**`MultiFrameEncoder`** — Splits payload into numbered QR frames.
- Frame format: `[total_frames(1)][frame_index(1)][payload_chunk(N)]`
- Max ~150 bytes data per frame (QR version 8, LOW ECC, byte mode)
- Typical signed PSBT (200-400 bytes) → 2-3 frames

**`MultiFrameDecoder`** — Reassembles frames from camera scans.
- Tracks received frames (boolean array)
- Deduplicates repeated scans
- Signals complete when all frames received

**`CameraScanner`** — MMAPI camera integration.
- `Manager.createPlayer("capture://video")` for camera access
- `VideoControl` for live viewfinder on Canvas
- Periodic `getSnapshot()` → feed to QrDecoder
- `PlayerListener` for lifecycle management

**`ManualEntryScreen`** — Manual PSBT hex input (fallback).
- Form with TextField for hex-encoded PSBT
- Validates format before proceeding

#### UI Updates — `ui/`

**`TransactionReviewScreen`** — Transaction approval screen.
- Shows: total send amount (BTC/sats), destination address(es), fee amount + percentage
- Warnings: high fee (>10%), multiple recipients, unknown change address
- Commands: "Sign" / "Reject"
- `TransactionReviewListener`: `onApprove()`, `onReject()`

**`QrDisplayScreen`** — Canvas-based QR rendering.
- Scales QR modules to fit 128x160 screen (2px/module for version 10 = 114x114)
- Multi-frame: frame counter at top, auto-advance timer (2s), loop
- Manual "Next"/"Prev" navigation
- Used for signed PSBT display and receive address QR

**`QrScanScreen`** — Camera viewfinder + QR scanning.
- MMAPI viewfinder display
- Progress: "Frame 2/3 received"
- "Enter Manually" fallback button

**Updated `WalletHomeScreen`** — Add "Sign Transaction" menu item.

**Updated `ReceiveScreen`** — QR code display + text (upgrade from text-only).

**Updated `BurnerWalletMIDlet`** — New navigation flow:
```
PIN → Home → [Receive | Sign | Settings]
                │        │
                │        ├→ QrScanScreen / ManualEntryScreen
                │        ├→ TransactionReviewScreen
                │        ├→ [signing]
                │        └→ QrDisplayScreen (signed PSBT)
                │
                └→ ReceiveScreen (QR + text)
```

### Companion (Rust)

#### Core Library — `companion/core/src/`

**`psbt.rs`** — PSBT construction and verification.
- `create_unsigned_psbt(wallet, recipients, fee_rate)` → `Psbt` (via BDK `build_tx`)
- `add_signature(psbt, signed_psbt_bytes)` → `Psbt`
- `finalize_psbt(psbt)` → `Transaction`
- `verify_signature(psbt, pubkey)` → bool
- `serialize_psbt(psbt)` / `deserialize_psbt(bytes)`

**`broadcast.rs`** — Transaction broadcasting via Esplora.

**`wallet.rs`** — BDK wallet management.
- `create_wallet(descriptor, network)` → `Wallet`
- `sync_wallet(wallet)` — sync UTXOs via Esplora
- `get_balance(wallet)` / `list_utxos(wallet)`

#### TUI — `companion/tui/`

- **Wallet status**: balance, UTXO count, sync status
- **Send flow**: recipient → amount → fee rate → build PSBT → display QR sequence
- **Receive signed PSBT**: camera scan (webcam) or paste hex → verify → finalize → broadcast
- **Transaction history**: list recent transactions

## Multi-Frame QR Protocol

```
Direction: Signer → Companion (signed PSBT display)
  Frame: [total(1)] [index(1)] [data(N)]
  Max data/frame: ~150 bytes (QR v8, LOW ECC, byte mode)
  Display: auto-cycle 2s per frame, loop continuously

Direction: Companion → Signer (unsigned PSBT via camera)
  Same frame format
  Companion displays animated QR sequence on screen
  Signer camera captures + decodes each frame
  MultiFrameDecoder tracks completion
```

## Testing Strategy

### Signer JUnit Tests

| Test class | Count (est.) | Focus |
|-----------|-------------|-------|
| CompactSizeTest | 6 | Varint encode/decode, edge cases |
| Secp256k1SignTest | 8 | ECDSA sign, verify, low-S, DER encoding |
| Bip143SighashTest | 6 | Segwit sighash, all sighash types |
| PsbtParserTest | 10 | Parse valid/invalid PSBTs, unknown keys |
| PsbtSignerTest | 8 | End-to-end sign, all sighash types, cross-impl |
| PsbtSerializerTest | 5 | Round-trip parse → sign → serialize → parse |
| TxSerializerTest | 6 | Transaction parse/serialize, witness format |
| QrEncoderTest | 6 | QR generation, version selection, ECC |
| MultiFrameTest | 8 | Split/reassemble, out-of-order, duplicates |
| QrDecoderTest | 5 | Decode generated QR images (round-trip) |
| TransactionReviewTest | 5 | Amounts, fees, warning detection |

**Estimated new signer tests: ~73**
**Total signer tests after M1c: ~226**

### Companion Rust Tests

| Test | Focus |
|------|-------|
| psbt_create_unsigned | Build PSBT with proper witness fields |
| psbt_add_signature | Merge partial sig |
| psbt_finalize | Construct broadcastable tx |
| psbt_verify_cross_impl | Verify Java ME signature |
| wallet_sync | BDK Esplora sync (testnet) |
| broadcast_testnet | Integration test |

### Cross-Implementation Vectors

New: `protocol/vectors/psbt-signing.json`
- Mnemonic: "abandon...about" (same as address vectors)
- Unsigned PSBT hex, expected BIP143 sighash, expected RFC 6979 signature (r, s), expected signed PSBT hex
- Both Rust and Java ME produce identical deterministic signatures

## Risk Areas

| Risk | Mitigation |
|------|------------|
| QR decoder too slow on Nokia ARM | Manual entry fallback is primary; camera is experimental |
| MMAPI not available on C1-01 | ManualEntryScreen works without camera |
| PSBT parser memory pressure | Streaming parser, no full DOM; null arrays after use |
| Nayuki QR port breaks on Java 1.4 | Port incrementally with tests; ~1000 lines manageable |
| BDK Esplora testnet unreliable | Mock Esplora responses for unit tests; integration tests optional |
| JAR size exceeds 1MB | Current: 286KB, budget: ~700KB. QR + PSBT estimated 50-150KB |
| ECDSA signing perf on Nokia | Single sign operation ~1-2s acceptable; no batch signing needed |

## Files to Create

### Signer (Java ME)
- `signer/src/org/burnerwallet/core/CompactSize.java`
- `signer/src/org/burnerwallet/chains/bitcoin/PsbtParser.java`
- `signer/src/org/burnerwallet/chains/bitcoin/PsbtTransaction.java`
- `signer/src/org/burnerwallet/chains/bitcoin/PsbtInput.java`
- `signer/src/org/burnerwallet/chains/bitcoin/PsbtOutput.java`
- `signer/src/org/burnerwallet/chains/bitcoin/PsbtSigner.java`
- `signer/src/org/burnerwallet/chains/bitcoin/PsbtSerializer.java`
- `signer/src/org/burnerwallet/chains/bitcoin/Bip143Sighash.java`
- `signer/src/org/burnerwallet/chains/bitcoin/TxSerializer.java`
- `signer/src/org/burnerwallet/transport/QrEncoder.java` (Nayuki port)
- `signer/src/org/burnerwallet/transport/QrDecoder.java`
- `signer/src/org/burnerwallet/transport/MultiFrameEncoder.java`
- `signer/src/org/burnerwallet/transport/MultiFrameDecoder.java`
- `signer/src/org/burnerwallet/transport/CameraScanner.java`
- `signer/src/org/burnerwallet/transport/ManualEntryScreen.java`
- `signer/src/org/burnerwallet/ui/TransactionReviewScreen.java`
- `signer/src/org/burnerwallet/ui/QrDisplayScreen.java`
- `signer/src/org/burnerwallet/ui/QrScanScreen.java`
- Tests: one test class per implementation class

### Companion (Rust)
- `companion/core/src/psbt.rs`
- `companion/core/src/broadcast.rs`
- `companion/core/src/wallet.rs`
- `companion/tui/src/` — TUI views and screens

### Protocol
- `protocol/vectors/psbt-signing.json`
- `protocol/schemas/multi-frame-qr.md` (protocol spec)

### Modify
- `signer/src/org/burnerwallet/chains/bitcoin/Secp256k1.java` — add ECDSA signing
- `signer/src/org/burnerwallet/ui/BurnerWalletMIDlet.java` — new navigation
- `signer/src/org/burnerwallet/ui/WalletHomeScreen.java` — add "Sign" menu
- `signer/src/org/burnerwallet/ui/ReceiveScreen.java` — QR display upgrade
- `signer/build.xml` — if transport classes need MMAPI stubs
- `companion/core/src/lib.rs` — export new modules
- `companion/core/Cargo.toml` — if new dependencies needed
- `CLAUDE.md` — update for M1c completion
