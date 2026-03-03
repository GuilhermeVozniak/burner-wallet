# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Burner Wallet is an air-gapped Bitcoin cold-storage wallet. An old Nokia C1-01 feature phone (Java ME) acts as the offline signer — it never touches the internet. A multi-platform companion ecosystem handles chain access, PSBT construction, and broadcasting. Data crosses the air gap via QR codes (primary), with Bluetooth OBEX, MicroSD, and manual text entry as fallbacks.

**Current milestone:** M1b complete, M1c next. M0 delivered Rust companion core (32 tests). M1a delivered Java ME signer crypto (114 tests). M1b delivered encrypted storage, PIN, and LCDUI screens (153 signer tests total). M1c will add PSBT parsing/signing and QR transport.

## Architecture

```
signer/              Java ME MIDlet (Nokia C1-01, CLDC 1.1 / MIDP 2.0)
companion/
  core/              Rust library — BIP39/32/44/84/173 crypto, rust-bitcoin + BDK
  tui/               Rust TUI binary (ratatui) — depends on core via path
  desktop/           Electron (placeholder)
  web/               Next.js (placeholder)
  extension/         Chrome Extension (placeholder)
  mobile/            React Native (placeholder)
protocol/
  schemas/           QR payload encoding schemas (planned)
  vectors/           BIP32/39/173 test vectors (JSON)
tools/
  freej2me-plus/     J2ME emulator (git submodule, not tracked)
  proguard/          ProGuard 7.8.2 (symlink, not tracked)
```

The **signer** is an extremely constrained Java ME environment (Java 1.4 source level, no generics, no autoboxing, no enhanced for-loop, no varargs, 1 MB JAR budget, ~286KB currently). It compiles against CLDC 1.1 + MIDP 2.0 stub JARs in `signer/lib/` and uses Bouncy Castle (`bcprov-jdk14`) for crypto, shrunk by ProGuard. Signer modules:
- `core/` — HashUtils, HexCodec, ByteArrayUtils, Base58, Bech32, CryptoError, AesUtils, EntropyCollector
- `chains/bitcoin/` — Secp256k1, Bip39Wordlist, Bip39Mnemonic, Bip32Key, Bip32Derivation, Bip44Path, BitcoinAddress, NetworkParams
- `storage/` — WalletStore, WalletData, RecordStoreAdapter, MidpRecordStoreAdapter
- `ui/` — BurnerWalletMIDlet, ScreenManager, PinScreen, OnboardingScreen, WalletHomeScreen, ReceiveScreen, SettingsScreen

The **companion core** (`companion/core/`) is the Rust crypto library. Modules: `mnemonic.rs` (BIP39), `keys.rs` (BIP32), `derivation.rs` (BIP44/84), `address.rs` (BIP84+BIP173 bech32), `network.rs`, `error.rs`.

Both signer and companion produce **identical addresses** for the same seed — verified via `protocol/vectors/cross-impl-wallet.json`.

## Build Commands

```bash
# Build everything
make build

# Build individual targets
make signer              # Requires JDK 8 (Zulu) + ProGuard
make companion-core      # cargo build in companion/core
make companion-tui       # cargo build in companion/tui

# Run all tests
make test

# Run specific test suites
cd companion/core && cargo test                    # 32 tests
cd companion/core && cargo test <test_name>        # single test
cd companion/tui && cargo test
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test  # 153 tests

# Lint
cd companion/core && cargo clippy -- -D warnings   # CI enforces -D warnings
cd companion/core && cargo fmt -- --check

# Signer JAR size check (must stay under 1 MB)
make size-check

# Launch signer in J2ME emulator
make emulator

# One-time tool setup (ProGuard + FreeJ2ME-Plus)
make setup-tools
```

## Commit Conventions

- **Conventional Commits** required: `<type>(<scope>): <description>`
- **Types:** `feat`, `fix`, `docs`, `test`, `chore`, `security`, `refactor`, `perf`, `ci`
- **Scopes:** `signer`, `companion-core`, `companion-tui`, `companion-web`, `companion-desktop`, `companion-mobile`, `companion-extension`, `protocol`, `crypto`, `storage`, `transport`, `ci`, `docs`, `spec`
- **DCO sign-off required** on all commits: `git commit -s`
- **Branch naming:** `feat/`, `fix/`, `docs/`, `chore/`, `security/`, `test/`, `refactor/`
- **Merge strategy:** squash merge via PRs against `main`

## Key Constraints

- **Signer (Java ME):** Java 1.4 source/target, compiled with JDK 8. No Java 5+ language features. Bootclasspath includes `signer/lib/cldcapi11.jar`, `signer/lib/midpapi20.jar`, and `rt.jar` (for BigInteger). ProGuard runs with `-microedition` and injects `bcprov-jdk14.jar` as `-injars` (not `-libraryjars`) for tree-shaking. JUnit tests compile with `source=1.8` in `signer/test/` and run on desktop JDK.
- **Companion Core (Rust):** Edition 2021, stable toolchain. Clippy warnings are errors in CI. Uses `bitcoin 0.32`, `bip39 2`, `bdk_wallet 1`.
- **CI matrix:** Companion runs on ubuntu/macos/windows. Signer CI runs on ubuntu with Temurin JDK 8.
- **Makefile hardcodes** `SIGNER_JAVA` to `/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home` for local dev.

## Security Notes

Changes touching cryptography, key storage, signing, or transport encoding require a `security` PR label and deterministic test vector coverage. See `SECURITY.md` for vulnerability reporting via GitHub Private Vulnerability Reporting.
