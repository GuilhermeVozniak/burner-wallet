---
name: psbt-guide
description: Implementation guide for BIP174 PSBT (Partially Signed Bitcoin Transactions) on the Java ME signer. Reference for M1c milestone. Use when working on PSBT parsing, signing, or transaction construction in signer/src/org/burnerwallet/transport/ or signer/src/org/burnerwallet/chains/bitcoin/.
---

# PSBT Implementation Guide (M1c Milestone)

BIP174 defines the Partially Signed Bitcoin Transaction (PSBT) format — the standard way to pass unsigned transactions across the air gap for offline signing.

## Architecture Overview

```
Companion (online)                    Air Gap                  Signer (offline Nokia)
─────────────────                    ─────────                ─────────────────────
1. Build PSBT from UTXOs      →  QR code  →     2. Parse PSBT
                                                  3. Display tx details
                                                  4. User confirms
                                                  5. Sign with private key
                               ←  QR code  ←     6. Return signed PSBT
7. Finalize & broadcast
```

## BIP174 PSBT Format

### Binary Structure

```
<magic: 0x70736274>       "psbt" in ASCII
<separator: 0xff>
<global-map>              Key-value pairs for global data
<separator: 0x00>
<input-map-1>             Key-value pairs for input 0
<separator: 0x00>
<input-map-2>             Key-value pairs for input 1
<separator: 0x00>
...
<output-map-1>            Key-value pairs for output 0
<separator: 0x00>
...
```

### Key-Value Encoding

Each entry:
```
<key-length: compact-size>
<key-type: 1 byte><key-data: variable>
<value-length: compact-size>
<value-data: variable>
```

### Compact Size Encoding

| Value Range | Encoding |
|-------------|----------|
| 0-0xFC | 1 byte |
| 0xFD-0xFFFF | 0xFD + 2 bytes LE |
| 0x10000-0xFFFFFFFF | 0xFE + 4 bytes LE |

### Global Map Key Types

| Key | Type | Description |
|-----|------|-------------|
| 0x00 | PSBT_GLOBAL_UNSIGNED_TX | The unsigned transaction |
| 0x01 | PSBT_GLOBAL_XPUB | Extended public key |
| 0xFB | PSBT_GLOBAL_VERSION | PSBT version (0 or 2) |

### Input Map Key Types (Most Important)

| Key | Type | Description |
|-----|------|-------------|
| 0x00 | PSBT_IN_NON_WITNESS_UTXO | Full previous transaction |
| 0x01 | PSBT_IN_WITNESS_UTXO | Previous output (segwit) |
| 0x02 | PSBT_IN_PARTIAL_SIG | Partial signature |
| 0x03 | PSBT_IN_SIGHASH_TYPE | Sighash type |
| 0x06 | PSBT_IN_BIP32_DERIVATION | BIP32 derivation path |

### Output Map Key Types

| Key | Type | Description |
|-----|------|-------------|
| 0x00 | PSBT_OUT_REDEEM_SCRIPT | Redeem script |
| 0x01 | PSBT_OUT_WITNESS_SCRIPT | Witness script |
| 0x02 | PSBT_OUT_BIP32_DERIVATION | BIP32 derivation path |

## Java ME Implementation Constraints

### Memory Management

The Nokia C1-01 has very limited heap. PSBT parsing must:
- Stream-parse, don't load entire PSBT into memory
- Process one key-value pair at a time
- Only retain essential fields (witness UTXO, derivation path, unsigned tx)
- Discard unknown key types immediately

### Byte Array Operations

No `ByteBuffer`, no streams. Use `ByteArrayUtils` from core:
```java
// Reading a compact size integer
public static long readCompactSize(byte[] data, int[] offset) {
    int first = data[offset[0]] & 0xFF;
    offset[0]++;
    if (first < 0xFD) {
        return first;
    } else if (first == 0xFD) {
        long val = (data[offset[0]] & 0xFF)
                 | ((data[offset[0] + 1] & 0xFF) << 8);
        offset[0] += 2;
        return val;
    } else if (first == 0xFE) {
        long val = (data[offset[0]] & 0xFF)
                 | ((data[offset[0] + 1] & 0xFF) << 8)
                 | ((data[offset[0] + 2] & 0xFF) << 16)
                 | ((long)(data[offset[0] + 3] & 0xFF) << 24);
        offset[0] += 4;
        return val;
    }
    // 0xFF: 8-byte - unlikely for PSBT on Nokia
    throw new CryptoError("CompactSize too large");
}
```

### Signing Flow

For P2WPKH (BIP84 native segwit), the sighash is computed as BIP143:

1. Extract the unsigned transaction from PSBT_GLOBAL_UNSIGNED_TX
2. For each input to sign:
   a. Look up PSBT_IN_WITNESS_UTXO for the previous output
   b. Look up PSBT_IN_BIP32_DERIVATION for the key path
   c. Derive the private key using Bip32Derivation
   d. Compute BIP143 sighash (double-SHA256 of serialized components)
   e. Sign with Secp256k1.sign()
3. Insert PSBT_IN_PARTIAL_SIG with the DER-encoded signature
4. Output the signed PSBT

### BIP143 Sighash (SegWit)

The preimage for segwit sighash:
```
nVersion (4 bytes LE)
hashPrevouts (32 bytes) = SHA256d(all input outpoints)
hashSequence (32 bytes) = SHA256d(all input sequences)
outpoint (36 bytes) = txid + vout of this input
scriptCode (variable) = OP_DUP OP_HASH160 <20-byte-hash> OP_EQUALVERIFY OP_CHECKSIG
amount (8 bytes LE) = value from witness UTXO
nSequence (4 bytes LE) = sequence of this input
hashOutputs (32 bytes) = SHA256d(all outputs)
nLockTime (4 bytes LE)
nHashType (4 bytes LE) = SIGHASH_ALL (0x01000000)
```

## QR Transport Encoding

PSBTs encoded for QR must be compact. Options:
1. **Base64 encoding** — Standard but larger
2. **Raw binary** — Most compact, requires binary QR mode
3. **Base43** (Electrum convention) — Good balance

For Nokia C1-01 QR display, consider splitting large PSBTs across multiple QR codes (animated sequence).

## Test Vectors

Add PSBT test vectors to `protocol/vectors/psbt-signing.json`:
```json
{
  "description": "P2WPKH single-input signing",
  "mnemonic": "abandon abandon ... about",
  "path": "m/84'/0'/0'/0/0",
  "psbt_hex": "70736274ff...",
  "expected_signed_hex": "70736274ff...",
  "expected_txid": "abc123..."
}
```

Both signer and companion must produce identical signed PSBTs.

## Recommended File Structure

```
signer/src/org/burnerwallet/
  transport/
    PsbtParser.java          # PSBT binary parsing
    PsbtSigner.java          # Signing logic
    PsbtSerializer.java      # Serialize signed PSBT
    CompactSize.java         # Compact size encoding/decoding
    QrEncoder.java           # PSBT → QR payload
    QrDecoder.java           # QR payload → PSBT
  chains/bitcoin/
    Bip143Sighash.java       # BIP143 segwit sighash computation
    TransactionSerializer.java # Raw transaction serialization
```

## References

- BIP174: https://github.com/bitcoin/bips/blob/master/bip-0174.mediawiki
- BIP143: https://github.com/bitcoin/bips/blob/master/bip-0143.mediawiki
- Rust reference: `bitcoin` crate's `Psbt` type (companion uses bitcoin 0.32)
