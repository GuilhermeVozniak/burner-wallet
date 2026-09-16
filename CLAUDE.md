# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Burner Wallet is an air-gapped Bitcoin cold-storage wallet. An old Nokia C1-01 feature phone (Java ME) acts as the offline signer — it never touches the internet. A multi-platform companion ecosystem handles chain access, PSBT construction, and broadcasting. Data crosses the air gap via QR codes (primary), with Bluetooth OBEX, MicroSD, and manual text entry as fallbacks.

**Security hardening pass (2026-09):** signer enforces SIGHASH_ALL and verifies every input against its `non_witness_utxo` (txid, amount, script) before signing; seed storage is blob v2 (random salt/IV, encrypt-then-MAC, 10-wrong-PIN wipe); companions verify every partial signature before finalizing (miniscript interpreter in the TUI, explicit secp256k1 check over the BIP143 digest in the web app, since btc-signer's `finalize()` does not verify); the web app builds real BIP174 PSBTs for the signer and finalizes its output; the TUI is watch-only (xpub descriptors) and checks signed PSBTs against the pending one; `Cargo.lock` files are tracked and CI builds with `--locked`. Test counts: 296 signer, 51 core, 38 TUI, 13 WASM (398 total). `protocol/vectors/psbt-signing.json` was regenerated with a real funding transaction.

**Device compatibility (2026-09-16):** until this date the shrunk signer JAR had only ever run on desktop JVMs; it embedded `bcprov-jdk14`, a J2SE build that needs `java.math.BigInteger`, `java.util.HashMap`, `java.lang.ThreadLocal`, `java.security.SecureRandom` and friends, none of which exist on CLDC 1.1, so the first key derivation on a Nokia would have thrown `NoClassDefFoundError`. Bouncy Castle was replaced by an in-house CLDC-safe crypto package (`signer/src/org/burnerwallet/core/crypto`: SHA-256/512, RIPEMD-160, HMAC, PBKDF2, AES, 256-bit modular arithmetic, secp256k1 Jacobian points, RFC 6979 ECDSA). The signer now compiles against only the CLDC/MIDP stubs, ProGuard treats unresolved references as errors, and `cd signer && ant cldc-audit` (blocking in CI) proves the shipped JAR references nothing outside CLDC 1.1 / MIDP 2.0. Bouncy Castle stays on the test classpath as a differential oracle (`CryptoOracleTest`). The emulator flow (import, PIN, receive QR, manual PSBT entry, review, sign, signed-PSBT QR) passes on the shrunk JAR and produces byte-identical signatures to the old build. Execution on physical hardware is still pending.

**Current milestone:** M3 complete, M4 next. M0 delivered Rust companion core (32 tests). M1a delivered Java ME signer crypto (114 tests). M1b delivered encrypted storage, PIN, and LCDUI screens (153 signer tests). M1c delivered PSBT parsing/signing, QR encode/decode/camera, and companion TUI (212 signer tests, 47 companion tests). M2 delivered TUI testing (30 tests), CI enablement, transaction history, camera QR pipeline, ImageProcessor, and Checkstyle (224 signer tests, 48 companion core tests, 30 TUI tests). M3 delivered multi-platform companions with real crypto: WASM bridge (13 tests), napi-rs bridge, web (Next.js + QR display/scan + Esplora), desktop (Electron), extension (Chrome), mobile (Expo). 315 total tests.

## Architecture

```
signer/              Java ME MIDlet (Nokia C1-01, CLDC 1.1 / MIDP 2.0)
companion/
  core/              Rust library — BIP39/32/44/84/173 crypto, rust-bitcoin + BDK
  core-wasm/         WASM bridge (wasm-bindgen) — crypto subset for web + extension
  core-napi/         Napi-rs bridge — full companion core for Electron desktop
  tui/               Rust TUI binary (ratatui) — depends on core via path
  desktop/           Electron app — wallet UI with inline crypto
  web/               Next.js app — wallet UI with JS crypto, QR display/scan, Esplora
  extension/         Chrome Extension — popup wallet with BIP39/84, chrome.storage
  mobile/            Expo/React Native — wallet UI with multi-screen navigation
protocol/
  schemas/           QR payload encoding schemas (planned)
  vectors/           BIP32/39/173 test vectors (JSON)
tools/
  freej2me-plus/     J2ME emulator (git submodule, not tracked)
  proguard/          ProGuard 7.8.2 (symlink, not tracked)
```

The **signer** is an extremely constrained Java ME environment (Java 1.4 source level, no generics, no autoboxing, no enhanced for-loop, no varargs, 1 MB JAR budget, ~156KB currently). It compiles against only the CLDC 1.1 + MIDP 2.0 stub JARs in `signer/lib/` (no `rt.jar`), ships its own CLDC-safe crypto in `core/crypto/`, and is shrunk and preverified by ProGuard with warnings fatal. Bouncy Castle (`bcprov-jdk14`) is a test-only oracle. Signer modules:
- `core/` — HashUtils, HexCodec, ByteArrayUtils, Base58, Bech32, CryptoError, AesUtils, EntropyCollector, CompactSize
- `core/crypto/` — Digest, Sha256, Sha512, Ripemd160, Hmac, Pbkdf2, Aes, Fe (256-bit arithmetic mod p and n), EcPoint (secp256k1 Jacobian, Montgomery ladder)
- `chains/bitcoin/` — Secp256k1, Bip39Wordlist, Bip39Mnemonic, Bip32Key, Bip32Derivation, Bip44Path, BitcoinAddress, NetworkParams, TxSerializer, TxData, TxInput, TxOutput, Bip143Sighash, PsbtParser, PsbtTransaction, PsbtInput, PsbtOutput, PsbtSigner, PsbtSerializer
- `storage/` — WalletStore, WalletData, RecordStoreAdapter, MidpRecordStoreAdapter
- `transport/` — QrCode, QrSegment, BitBuffer, QrDecoder, MultiFrameEncoder, MultiFrameDecoder, CameraScanner, ImageProcessor, ManualEntryScreen
- `ui/` — BurnerWalletMIDlet, ScreenManager, PinScreen, OnboardingScreen, WalletHomeScreen, ReceiveScreen, SettingsScreen, QrDisplayScreen, QrScanScreen, TransactionReviewScreen

The **companion core** (`companion/core/`) is the Rust crypto + wallet library. Modules: `mnemonic.rs` (BIP39), `keys.rs` (BIP32), `derivation.rs` (BIP44/84), `address.rs` (BIP84+BIP173 bech32), `network.rs`, `error.rs`, `wallet.rs` (BDK wallet management), `psbt.rs` (PSBT construction/merge/finalize), `broadcast.rs` (Esplora broadcasting).

The **companion TUI** (`companion/tui/`) is a full ratatui terminal UI with wallet status, send flow (PSBT construction), receive signed PSBT, and transaction broadcasting.

The **companion WASM bridge** (`companion/core-wasm/`) exposes the crypto-only subset via wasm-bindgen: mnemonic gen/validate, seed derivation, BIP84 address derivation, PSBT base64/hex conversion. Does NOT include BDK/Esplora (networking stays in JS).

The **companion napi-rs bridge** (`companion/core-napi/`) wraps the full companion core for Electron via napi-rs, including blocking Esplora wallet sync.

The **companion web** (`companion/web/`) is a Next.js 15 app with real BIP39/BIP84 crypto (bip39 + @scure/bip32 + @noble/hashes), QR code display (qrcode), webcam QR scanning (html5-qrcode), and Esplora API integration for balance sync and broadcasting.

Both signer and companion produce **identical addresses and signatures** for the same seed — verified via `protocol/vectors/cross-impl-wallet.json` and `protocol/vectors/psbt-signing.json`.

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
cd companion/core && cargo test                    # 51 tests
cd companion/core && cargo test <test_name>        # single test
cd companion/tui && cargo test
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant test  # 296 tests

# Lint
cd companion/core && cargo clippy -- -D warnings   # CI enforces -D warnings
cd companion/core && cargo fmt -- --check

# Signer JAR size check (must stay under 1 MB)
make size-check

# Fail if the shrunk signer JAR references any class CLDC 1.1 / MIDP 2.0 lacks (must pass)
cd signer && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ant cldc-audit

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

- **Signer (Java ME):** Java 1.4 source/target, compiled with JDK 8. No Java 5+ language features. Bootclasspath is only `signer/lib/cldcapi11.jar` and `signer/lib/midpapi20.jar`, so any non-CLDC API fails to compile. ProGuard runs with `-microedition` over the signer classes alone and treats unresolved references as errors; `ant cldc-audit` re-checks the shipped JAR. `bcprov-jdk14.jar` is on the test classpath only, as the differential oracle in `CryptoOracleTest`. JUnit tests compile with `source=1.8` in `signer/test/` and run on desktop JDK.
- **Companion Core (Rust):** Edition 2021, stable toolchain. Clippy warnings are errors in CI. Uses `bitcoin 0.32`, `bip39 2`, `bdk_wallet 1`.
- **CI matrix:** Companion runs on ubuntu/macos/windows. Signer CI runs on ubuntu with Temurin JDK 8.
- **Makefile hardcodes** `SIGNER_JAVA` to `/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home` for local dev.

## Security Notes

Changes touching cryptography, key storage, signing, or transport encoding require a `security` PR label and deterministic test vector coverage. See `SECURITY.md` for vulnerability reporting via GitHub Private Vulnerability Reporting.
