---
name: cross-verify
description: Verify that the Java ME signer and Rust companion produce identical outputs for the same cryptographic inputs. Use after modifying any BIP39/32/44/84/173 code in either signer/ or companion/core/, or when updating protocol/vectors/.
---

# Cross-Implementation Verification

The burner-wallet has two independent crypto implementations that MUST produce byte-identical results:

| Operation | Signer (Java ME) | Companion (Rust) |
|-----------|-------------------|-------------------|
| Mnemonic → Seed | Bip39Mnemonic | mnemonic.rs |
| Seed → Master Key | Bip32Key | keys.rs |
| Key Derivation | Bip32Derivation | derivation.rs |
| Path Parsing | Bip44Path | derivation.rs |
| Address Generation | BitcoinAddress | address.rs |
| Bech32 Encoding | Bech32 | address.rs (via bitcoin crate) |
| Base58Check | Base58 | (via bitcoin crate) |

## Verification Process

### Step 1: Run Both Test Suites

```bash
# Signer (153 tests)
cd signer && JAVA_HOME=$SIGNER_JAVA ant test

# Companion (32 tests)
cd companion/core && cargo test
```

Both must pass with zero failures.

### Step 2: Check Cross-Implementation Vectors

The source of truth is `protocol/vectors/cross-impl-wallet.json`. This file contains:
- A known mnemonic
- Expected master key (xprv/xpub)
- Expected derived keys at standard BIP44/84 paths
- Expected addresses (P2PKH, P2SH-P2WPKH, P2WPKH / bech32)

```bash
cat protocol/vectors/cross-impl-wallet.json
```

Both implementations reference this file in their tests:
- Signer: `signer/test/org/burnerwallet/chains/bitcoin/AddressTest.java`
- Companion: `companion/core/tests/integration.rs`

### Step 3: Verify No Drift

If you changed any crypto code, run a diff check:

```bash
# Ensure both still reference the same vector file
grep -r "cross-impl-wallet" signer/test/ companion/core/tests/
```

### Adding New Vectors

When adding new functionality (e.g., PSBT signing), add vectors to `protocol/vectors/`:

1. Create the vector file: `protocol/vectors/<feature>.json`
2. Define inputs and expected outputs
3. Reference from both signer and companion tests
4. Ensure both implementations produce identical results

### Vector File Format

```json
{
  "description": "Description of what this tests",
  "vectors": [
    {
      "description": "Test case description",
      "input": {
        "mnemonic": "abandon abandon abandon ...",
        "passphrase": "",
        "network": "mainnet",
        "path": "m/84'/0'/0'/0/0"
      },
      "expected": {
        "seed_hex": "5eb00bbddcf069...",
        "master_xprv": "xprv9s21ZrQH143K...",
        "derived_xpub": "xpub6CatWDiKy...",
        "address": "bc1q..."
      }
    }
  ]
}
```

## Common Divergence Sources

Watch for these when implementations disagree:

1. **Endianness:** Bitcoin uses little-endian for most serialization
2. **Hardened derivation index:** Must be `index + 0x80000000`, not `index | 0x80000000` (same for positive values, differs for edge cases)
3. **HMAC key:** BIP32 master key uses "Bitcoin seed" as HMAC key
4. **Bech32 witness version:** v0 for P2WPKH, encoded as OP_0 (0x00)
5. **String encoding:** Mnemonic passphrase MUST be NFKD-normalized UTF-8
6. **BigInteger handling:** Java's BigInteger is signed; ensure no sign issues in scalar operations
