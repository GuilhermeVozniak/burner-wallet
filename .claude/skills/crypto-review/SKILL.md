---
name: crypto-review
description: Security-focused review for cryptographic code in signer/ and companion/core/. Validates BIP standard compliance, checks for timing side-channels, verifies test vector coverage, and ensures cross-implementation consistency. Use when modifying files in signer/src/org/burnerwallet/core/, signer/src/org/burnerwallet/chains/, or companion/core/src/.
---

# Cryptographic Code Security Review

This project is an air-gapped Bitcoin cold-storage wallet. Cryptographic correctness is paramount — a single bug could lose funds irrecoverably. Follow this review process for ANY change touching crypto code.

## Scope

Code requiring this review:

**Signer (Java ME):**
- `signer/src/org/burnerwallet/core/` — HashUtils, AesUtils, EntropyCollector, HexCodec, Base58, Bech32
- `signer/src/org/burnerwallet/chains/bitcoin/` — Secp256k1, Bip39*, Bip32*, BitcoinAddress, NetworkParams
- `signer/src/org/burnerwallet/storage/` — WalletStore (handles encrypted seed)

**Companion (Rust):**
- `companion/core/src/` — mnemonic.rs, keys.rs, derivation.rs, address.rs, network.rs

## Review Checklist

### 1. BIP Standard Compliance

For each BIP implementation, verify against the specification:

| BIP | Spec | Key Verification |
|-----|------|-----------------|
| BIP39 | Mnemonic → seed derivation | PBKDF2-HMAC-SHA512, 2048 rounds, "mnemonic" + passphrase salt |
| BIP32 | HD key derivation | HMAC-SHA512, hardened (index >= 0x80000000) vs normal |
| BIP44 | Path structure | m/44'/0'/0' for Bitcoin mainnet legacy |
| BIP84 | Native SegWit paths | m/84'/0'/0' for bc1 addresses |
| BIP173 | Bech32 encoding | 6-char checksum, HRP = "bc" (mainnet) / "tb" (testnet) |

### 2. Test Vector Coverage

Every crypto function MUST be covered by test vectors from `protocol/vectors/`:

```bash
# Verify test vectors exist and are referenced
ls protocol/vectors/
# Expected: bip39-english.json, bip32-derivation.json, bip173-bech32.json, cross-impl-wallet.json
```

New crypto code MUST add vectors to the appropriate file or create new ones.

### 3. Cross-Implementation Consistency

The signer (Java ME) and companion (Rust) MUST produce **identical outputs** for the same inputs. This is verified by `protocol/vectors/cross-impl-wallet.json`.

After any crypto change:
```bash
# Run both test suites
cd companion/core && cargo test
cd signer && JAVA_HOME=$SIGNER_JAVA ant test

# Verify cross-implementation vectors still pass
grep -l "cross-impl" signer/test/ companion/core/tests/
```

### 4. Constant-Time Operations

**Critical for private key operations.** Check for:

- **No branching on secret data:** `if (secretByte == 0)` leaks timing
- **No early returns based on secrets:** Compare all bytes, then decide
- **No variable-length operations on secrets:** Loop bounds must not depend on key material
- **Use constant-time comparison:** Never use `Arrays.equals()` or `==` for secret comparisons

**Java ME constant-time compare pattern:**
```java
// GOOD
private static boolean constantTimeEquals(byte[] a, byte[] b) {
    if (a.length != b.length) return false;
    int result = 0;
    for (int i = 0; i < a.length; i++) {
        result |= a[i] ^ b[i];
    }
    return result == 0;
}
```

**Rust constant-time:** Use `subtle` crate's `ConstantTimeEq` or equivalent.

### 5. Key Material Hygiene

- **Zero after use:** Overwrite byte arrays containing seeds, private keys, or mnemonics with zeros after use
- **No logging:** Never log, print, or include key material in error messages
- **No serialization:** Key material should not implement Serializable (Java) or Debug (Rust, for private types)
- **Scope minimization:** Keep private key variables in the tightest possible scope

**Java ME zeroing pattern:**
```java
byte[] seed = deriveSeed(mnemonic);
try {
    // use seed...
} finally {
    for (int i = 0; i < seed.length; i++) {
        seed[i] = 0;
    }
}
```

### 6. Entropy Quality

The signer's `EntropyCollector` gathers entropy from keypad timing. Verify:
- Minimum entropy threshold before seed generation
- Entropy is mixed with a CSPRNG or hash function
- No predictable fallback (do NOT use `System.currentTimeMillis()` alone)

### 7. Storage Encryption

`WalletStore` encrypts seeds with PIN-derived keys via PBKDF2 + AES-256-CBC:
- PBKDF2 iteration count must be >= 10,000 (balance security vs. Nokia performance)
- Salt must be random, stored alongside ciphertext
- IV must be random per encryption, never reused
- PIN stretching must use HMAC-SHA256 or HMAC-SHA512

### 8. Network Isolation (Air Gap)

The signer MUST NEVER:
- Import any networking classes (`javax.microedition.io.*` for HTTP/socket)
- Include code paths that could be tricked into making network calls
- Store URLs, hostnames, or IP addresses

Verify: `grep -r "microedition.io\|HttpConnection\|SocketConnection\|DatagramConnection" signer/src/` should return **zero results**.

## Severity Classification

| Severity | Definition | Action |
|----------|-----------|--------|
| **CRITICAL** | Could lose funds, leak keys, or break air gap | Block merge. Fix immediately. |
| **HIGH** | Weak crypto parameter, missing zeroing, timing leak | Block merge. Fix before release. |
| **MEDIUM** | Missing test vector, suboptimal but safe pattern | Request changes. |
| **LOW** | Style, documentation, naming | Suggest improvement. |

## PR Requirements for Crypto Changes

- `security` label on the PR
- Deterministic test vector coverage for new/modified paths
- Cross-implementation vector update if address derivation changes
- Review checklist completed in PR description
