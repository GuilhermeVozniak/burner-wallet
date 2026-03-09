# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Companion TUI unit tests: 30 tests covering app state machine, screen transitions, send/receive flows
- CI signer workflow: enabled build, test (224 tests), and JAR size check
- **Transaction history (TUI):** BDK wallet transaction listing with confirmed/unconfirmed status, net amounts, and txid display
- **Transaction listing API (companion core):** `get_transactions()` returns `TxSummary` structs with sent/received/net/confirmed fields
- **Image processor (signer):** `ImageProcessor` class for camera snapshot-to-grayscale-to-threshold pipeline (12 tests)
- **Camera QR pipeline (signer):** Connected `ImageProcessor` + `QrDecoder` + `MultiFrameDecoder` in `QrScanScreen` for live QR scanning
- **CI companion:** Added clippy and rustfmt checks for TUI in ci-companion workflow
- **Signer Checkstyle:** Added `checkstyle.xml` config and wired `ant check` target to Checkstyle 10.21.4
- **Makefile:** Added `lint-companion-tui` target and Checkstyle download to `setup-tools`
- **Formatting:** Applied `cargo fmt` to companion core and TUI sources
- **Web scaffold (Next.js 15):** Placeholder app at `companion/web/` with build + lint CI
- **Desktop scaffold (Electron):** Placeholder app at `companion/desktop/` with TypeScript build CI
- **Extension scaffold (Chrome Manifest V3):** Placeholder popup at `companion/extension/` with build CI
- **Mobile scaffold (Expo/React Native):** Placeholder app at `companion/mobile/` with type-check CI
- **CI companion:** Enabled web, desktop, extension, mobile jobs in ci-companion workflow
- **Makefile:** Added build/lint/clean targets for all 4 companion platforms
- **WASM bridge (companion core-wasm):** wasm-bindgen crate exposing BIP39/BIP84/PSBT crypto for web and extension (13 tests with cross-impl address vectors)
- **Napi-rs bridge (companion core-napi):** Native Node.js module wrapping companion core for Electron desktop, including blocking Esplora sync
- **Web wallet UI:** Full Next.js wallet with real BIP39 mnemonic generation/validation, BIP84 address derivation, Esplora balance sync, transaction broadcasting, QR code display for addresses and transactions, webcam QR scanning for signed PSBTs
- **Extension wallet UI:** Chrome extension popup with mnemonic gen/import, address derivation, balance sync, chrome.storage.local persistence
- **Desktop wallet UI:** Electron app with inline crypto (BIP39/BIP32/BIP84), RIPEMD-160, bech32, Esplora balance sync
- **Mobile wallet UI:** Expo/React Native app with multi-screen wallet (home, import, dashboard), crypto via react-native-get-random-values

## [0.1.0-m1c] - 2026-03-04

### Added

- **PSBT signing (signer):** BIP174 PSBT parser, signer, and serializer on Java ME
- **ECDSA signing (signer):** RFC 6979 deterministic signatures with low-S normalization (BIP 62/146)
- **BIP143 sighash (signer):** SegWit transaction digest for P2WPKH inputs
- **QR encode (signer):** Nayuki QR generator ported to Java 1.4 (~1200 lines)
- **QR decode (signer):** QR decoder with Reed-Solomon error correction
- **Multi-frame QR protocol (signer):** Encoder/decoder for splitting large payloads across multiple QR codes
- **Camera scanner (signer):** MMAPI-based camera capture with QR scan screen
- **Transaction review screen (signer):** LCDUI screen for reviewing PSBT details before signing
- **QR display screen (signer):** Animated multi-frame QR display for signed PSBTs
- **Companion TUI:** Full ratatui terminal UI with wallet creation, send flow (PSBT construction), receive signed PSBT, and broadcasting
- **Companion PSBT module:** Create, merge, finalize, serialize/deserialize PSBTs via BDK
- **Companion broadcast module:** Transaction broadcasting via Esplora
- **Cross-implementation PSBT signing vectors:** Deterministic signatures verified across Java ME and Rust
- 212 signer tests, 47 companion tests

## [0.1.0-m1b] - 2026-03-03

### Added

- **Encrypted storage (signer):** AES-256-CBC encryption of wallet data in MIDP RecordStore
- **PIN entry (signer):** PBKDF2 PIN-to-key derivation (5000 iterations) with LCDUI PIN screen
- **Wallet data model (signer):** WalletStore with RecordStoreAdapter abstraction for testability
- **Onboarding screen (signer):** Mnemonic generation, display, and confirmation flow
- **Wallet home screen (signer):** Balance display, address, and action menu
- **Receive screen (signer):** Address display with QR code
- **Settings screen (signer):** Network toggle (mainnet/testnet)
- **Screen manager (signer):** LCDUI screen navigation framework
- **Entropy collector (signer):** Canvas-based user interaction entropy mixing
- 153 signer tests

## [0.1.0-m1a] - 2026-03-03

### Added

- **secp256k1 (signer):** Elliptic curve operations via Bouncy Castle on Java ME
- **BIP39 (signer):** Mnemonic generation, validation, and seed derivation with TREZOR test vectors
- **BIP32 (signer):** HD key derivation (master + child keys) with BIP32 test vectors 1-3
- **BIP44/84 (signer):** Derivation path construction for Bitcoin mainnet and testnet
- **BIP173 (signer):** Bech32 address encoding and decoding
- **Core utilities (signer):** HashUtils, HexCodec, ByteArrayUtils, Base58, AesUtils, CompactSize
- **ProGuard integration:** Bouncy Castle shrunk from 4.5 MB to ~350 KB
- 114 signer tests

## [0.1.0-m0] - 2026-03-02

### Added

- Initial project scaffold: signer (Java ME), companion (Rust core + TUI), protocol layer
- **Companion core (Rust):** BIP39 mnemonic, BIP32 key derivation, BIP44/84 paths, BIP173 addresses, wallet management via BDK
- **Companion network config:** Testnet/mainnet/signet selection with Esplora URLs
- Project documentation: README, CONTRIBUTING, SECURITY, CODE_OF_CONDUCT, CHANGELOG
- CI/CD workflows for signer and companion builds
- BIP32/39/173 test vectors from reference implementations
- Cross-implementation address verification (Java ME signer matches Rust companion)
- 32 companion tests
