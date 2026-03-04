---
name: commit
description: Create a git commit following this project's Conventional Commits format with DCO sign-off. Use when the user asks to commit changes or when code changes are ready to be committed.
disable-model-invocation: true
---

# Commit Convention Enforcer

This project uses strict Conventional Commits with DCO sign-off. Follow this process exactly.

## Commit Format

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]

Signed-off-by: Name <email>
Co-Authored-By: Claude <noreply@anthropic.com>
```

## Valid Types

| Type | When to Use |
|------|-------------|
| `feat` | New feature or capability |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `test` | Adding or fixing tests |
| `chore` | Build, CI, tooling changes |
| `security` | Security-related changes |
| `refactor` | Code change that neither fixes nor adds |
| `perf` | Performance improvement |
| `ci` | CI/CD configuration |

## Valid Scopes

| Scope | Directory/Area |
|-------|---------------|
| `signer` | `signer/` (Java ME MIDlet) |
| `companion-core` | `companion/core/` (Rust library) |
| `companion-tui` | `companion/tui/` (Rust TUI) |
| `companion-web` | `companion/web/` |
| `companion-desktop` | `companion/desktop/` |
| `companion-mobile` | `companion/mobile/` |
| `companion-extension` | `companion/extension/` |
| `protocol` | `protocol/` (schemas, vectors) |
| `crypto` | Cross-cutting crypto changes |
| `storage` | Storage layer changes |
| `transport` | QR/Bluetooth/MicroSD transport |
| `ci` | `.github/workflows/` |
| `docs` | Documentation |
| `spec` | Specification documents |

## Process

1. **Check what changed:**
   ```bash
   git status
   git diff --staged
   git diff
   ```

2. **Determine type and scope** from the files changed.

3. **Write a concise description** (imperative mood, lowercase, no period):
   - Good: `feat(signer): add PSBT parser for segwit transactions`
   - Bad: `feat(signer): Added PSBT parser.`

4. **Stage specific files** (never `git add -A`):
   ```bash
   git add signer/src/org/burnerwallet/transport/PsbtParser.java
   git add signer/test/org/burnerwallet/transport/PsbtParserTest.java
   ```

5. **Commit with sign-off:**
   ```bash
   git commit -s -m "$(cat <<'EOF'
   feat(signer): add PSBT parser for segwit transactions

   Implements BIP174 PSBT v0 parsing with support for single-input
   P2WPKH transactions. Validates input/output maps and extracts
   the sighash for offline signing.

   Co-Authored-By: Claude <noreply@anthropic.com>
   EOF
   )"
   ```

## Security-Sensitive Changes

If the commit touches ANY of these areas, remind the user to add the `security` label to the PR:
- Cryptographic operations (BIP39, BIP32, Secp256k1, AES)
- Key storage or PIN handling (WalletStore, AesUtils)
- Signing or transaction construction (PSBT)
- Transport encoding (QR payload format)
- Entropy collection

## Pre-Commit Checks

Before committing, verify:
- [ ] Tests pass: `make test` (or subset for changed area)
- [ ] Lint passes: `make lint` (for companion changes)
- [ ] Size check: `make size-check` (for signer changes)
- [ ] No secrets in staged files (no private keys, no seed phrases)
- [ ] No absolute paths in code (only in Makefile SIGNER_JAVA)

## Branch Naming

If creating a new branch:
```
feat/psbt-parser
fix/bip32-hardened-derivation
docs/m1c-design
chore/update-proguard
security/constant-time-compare
test/cross-impl-vectors
refactor/wallet-store-api
```
