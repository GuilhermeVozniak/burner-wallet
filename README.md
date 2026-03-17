# Burner Wallet

**Air-gapped Bitcoin wallet for Nokia feature phones.**

A security-first, offline-signing Bitcoin wallet built for Java ME (Series 40) devices. The Nokia C1-01 serves as a strict air-gapped signer — it never touches the internet. A multi-platform companion ecosystem handles chain access, transaction construction, and broadcasting.

> **Status:** Pre-alpha — Architecture & specification phase. Not yet suitable for real funds.

[![CI Signer](https://github.com/GuilhermeVozniak/burner-wallet/actions/workflows/ci-signer.yml/badge.svg)](https://github.com/GuilhermeVozniak/burner-wallet/actions/workflows/ci-signer.yml)
[![CI Companion](https://github.com/GuilhermeVozniak/burner-wallet/actions/workflows/ci-companion.yml/badge.svg)](https://github.com/GuilhermeVozniak/burner-wallet/actions/workflows/ci-companion.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Table of Contents

- [Problem](#problem)
- [How It Works](#how-it-works)
- [Key Features](#key-features)
- [Installation (Pre-built Releases)](#installation-pre-built-releases)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Getting Started (Build from Source)](#getting-started-build-from-source)
- [Development Workflow](#development-workflow)
- [Testing](#testing)
- [CI/CD](#cicd)
- [Versioning](#versioning)
- [Roadmap](#roadmap)
- [Security](#security)
- [Contributing](#contributing)
- [Project Documentation](#project-documentation)
- [License](#license)
- [FAQ](#faq)

---

## Problem

Hardware wallets are excellent, but they are expensive, proprietary, and require trust in opaque firmware. Meanwhile, billions of retired Nokia feature phones sit in drawers — devices with physical keypads, minimal attack surface, and cameras just good enough for QR codes.

Burner Wallet turns a Nokia C1-01 (or compatible Series 40 device) into a dedicated Bitcoin cold-storage signer. The air gap is enforced by the device itself: no Wi-Fi, no IP data channel — only QR codes cross the boundary. When QR isn't available, fallback transports (Bluetooth OBEX push, MicroSD sneakernet, manual text entry) maintain the air gap with zero network stack involvement.

## How It Works

```
┌─────────────────┐                                 ┌──────────────────┐
│                 │       QR (unsigned PSBT)         │                  │
│   Companion     │ ──────────────────────────────►  │   Signer         │
│   (Online)      │                                  │   (Nokia C1-01)  │
│                 │  ◄──────────────────────────────  │   Air-gapped     │
│   TUI | Desktop │       QR (signed PSBT)           │                  │
│   Web | Mobile  │                                  └──────────────────┘
│   Extension     │
└────────┬────────┘
         │
         │  Broadcast signed tx
         ▼
   ┌──────────┐
   │ Bitcoin  │
   │ Network  │
   └──────────┘
```

**Signer (offline):** Generates seeds, stores encrypted keys, derives addresses, signs PSBTs, scans and displays QR codes.

**Companion (online):** Syncs UTXOs, estimates fees, constructs unsigned PSBTs, scans signed QR codes from signer, broadcasts transactions.

### Transport Methods

| Method | Direction | Primary / Fallback |
|--------|-----------|--------------------|
| **QR code scan** (camera) | Companion → Signer | Primary |
| **QR code display** (screen) | Signer → Companion | Primary |
| **Bluetooth OBEX push** | Both directions | Fallback — fire-and-forget file transfer, no pairing data channel |
| **MicroSD card** | Both directions | Fallback — write file to card, physically move between devices |
| **Manual text entry** | Companion → Signer | Fallback — compact encoded payload typed on Nokia keypad |

## Key Features

- **Strict air gap** — Signer device has no IP networking; transport is physical-layer only
- **Multi-network** — Supports both Bitcoin mainnet and testnet/signet with user-selectable toggle
- **Multi-chain ready** — Module boundaries designed for future chain extensibility (ETH, Liquid, etc.)
- **BIP-standard interoperability** — BIP39 mnemonics (with optional passphrase), BIP32/44/84 HD derivation, BIP173 bech32 addresses, BIP174 PSBTs
- **Minimal attack surface** — Java ME sandbox on a device with no OS-level app store, no browser, no background processes
- **Recovery via mnemonic** — Standard 12/24-word backup compatible with any BIP39 wallet
- **BIP39 passphrase** — Optional 25th-word support for additional seed protection and plausible deniability
- **Encrypted key storage** — Seeds encrypted at rest in MIDP RecordStore with PIN-derived key
- **Multiple transport options** — QR (primary), Bluetooth OBEX, MicroSD, manual entry as fallbacks
- **Multi-platform companion** — TUI, desktop (Electron), web (Next.js), Chrome extension, mobile (React Native)
- **Deterministic builds** — Reproducible JAR output for independent verification

## Installation (Pre-built Releases)

If you want to use Burner Wallet without compiling anything, download the pre-built binaries from the [GitHub Releases](https://github.com/GuilhermeVozniak/burner-wallet/releases) page.

### 1. Download

Go to the [latest release](https://github.com/GuilhermeVozniak/burner-wallet/releases/latest) and download the files you need:

**Signer (Nokia phone):**

| File | Description |
|------|-------------|
| `BurnerWallet.jar` | MIDlet application for the Nokia phone |
| `BurnerWallet.jad` | MIDlet descriptor (required for installation) |

**Companion (your computer/phone):**

| File | Platform |
|------|----------|
| `burner-companion-macos-apple-silicon` | macOS (Apple Silicon — M1/M2/M3/M4) |
| `burner-companion-macos-intel` | macOS (Intel) |
| `burner-companion-linux-amd64` | Linux (x86_64) |
| `burner-companion-linux-arm64` | Linux (ARM64 / Raspberry Pi) |
| `burner-companion-windows-amd64.exe` | Windows (x86_64) |
| `burner-companion-web.tar.gz` | Self-hostable web companion |

**Verification:**

| File | Description |
|------|-------------|
| `SHA256SUMS.txt` | SHA-256 checksums for all artifacts |

### 2. Verify Downloads

Always verify the integrity of downloaded files:

```bash
# Download the checksums file alongside your artifacts, then verify:
sha256sum -c SHA256SUMS.txt

# Or verify a single file:
sha256sum BurnerWallet.jar
# Compare the output with the hash in SHA256SUMS.txt
```

On macOS, use `shasum -a 256` instead of `sha256sum`.

### 3. Install the Signer on a Nokia Phone

Transfer both `BurnerWallet.jar` and `BurnerWallet.jad` to your Nokia C1-01 (or compatible Series 40 device) using one of these methods:

**Via Bluetooth:**
1. Enable Bluetooth on both your computer and the Nokia phone
2. Pair the devices
3. Send `BurnerWallet.jar` via Bluetooth file transfer (OBEX push)
4. The phone will prompt you to install — accept

**Via USB cable:**
1. Connect the Nokia to your computer with a USB data cable
2. The phone should appear as a mass storage device
3. Copy `BurnerWallet.jar` and `BurnerWallet.jad` to the phone's storage
4. On the phone, navigate to the file using the file manager and select it to install

**Via MicroSD card:**
1. Insert the MicroSD card into your computer (with an adapter if needed)
2. Copy `BurnerWallet.jar` and `BurnerWallet.jad` to the card
3. Insert the card into the Nokia phone
4. Navigate to the file on the card using the file manager and select it to install

Once installed, the MIDlet appears in the phone's application menu. Launch it and follow the on-screen setup:
1. **Set a PIN** — protects wallet access on the device
2. **Generate or import a mnemonic** — creates your BIP39 seed phrase (write it down!)
3. **Wallet home** — view your receive address, sign transactions via QR

> **Important:** After setup, your Nokia becomes your air-gapped signer. It should never connect to the internet. The phone's lack of Wi-Fi enforces this naturally.

### 4. Install the Companion on Your Computer

**macOS (Apple Silicon — M1/M2/M3/M4):**

```bash
# Make the binary executable
chmod +x burner-companion-macos-apple-silicon

# Remove quarantine attribute (macOS will block unsigned binaries otherwise)
xattr -d com.apple.quarantine burner-companion-macos-apple-silicon

# Run
./burner-companion-macos-apple-silicon
```

**macOS (Intel):**

```bash
chmod +x burner-companion-macos-intel
xattr -d com.apple.quarantine burner-companion-macos-intel
./burner-companion-macos-intel
```

**Linux:**

```bash
chmod +x burner-companion-linux-amd64   # or linux-arm64
./burner-companion-linux-amd64
```

**Windows:**

Double-click `burner-companion-windows-amd64.exe`, or run from a terminal:

```powershell
.\burner-companion-windows-amd64.exe
```

**Web (self-hosted):**

```bash
tar -xzf burner-companion-web.tar.gz -C ./companion-web
# Serve with any static file server, e.g.:
npx serve ./companion-web
```

### 5. Using the Air-Gapped Workflow

Once both signer and companion are running:

1. **Companion:** Create a transaction (enter recipient address, amount) — the companion constructs an unsigned PSBT and displays it as a QR code
2. **Signer (Nokia):** Use the phone's camera to scan the QR code from the companion screen
3. **Signer (Nokia):** Review the transaction details on the phone screen, then approve to sign
4. **Signer (Nokia):** The phone displays the signed PSBT as a QR code on its screen
5. **Companion:** Scan the signed QR code from the phone screen (using webcam or device camera)
6. **Companion:** Broadcast the signed transaction to the Bitcoin network

The signer never touches the internet. All data crosses the air gap via QR codes only.

### 6. Run the Signer in an Emulator (No Nokia Phone Required)

If you don't have a Nokia phone, you can test the signer in the FreeJ2ME-Plus emulator on your computer. This requires building from source — see [Getting Started (Build from Source)](#getting-started-build-from-source).

---

## Architecture

The project is organized as a monorepo with the signer, multiple companion apps, and a shared protocol layer:

```
burner-wallet/
├── signer/                  # Java ME MIDlet — runs on Nokia C1-01
│   └── src/org/burnerwallet/
│       ├── core/            # core-crypto (chain-agnostic interfaces)
│       ├── chains/          # chain-specific implementations (bitcoin first)
│       ├── wallet/          # wallet-domain
│       ├── transport/       # transport-qr + fallbacks
│       ├── storage/         # storage-secure
│       └── ui/              # ui-midlet (LCDUI)
├── companion/
│   ├── core/                # Shared Rust library (rust-bitcoin, BDK)
│   ├── tui/                 # Terminal UI (ratatui)
│   ├── desktop/             # Electron app
│   ├── web/                 # Next.js app + landing page
│   ├── extension/           # Chrome extension
│   └── mobile/              # React Native app
├── protocol/                # Shared protocol specs, schemas & test vectors
│   ├── schemas/             # QR payload encoding schemas
│   └── vectors/             # BIP32/39/173/174 test vectors
├── docs/                    # PRD, technical spec, security profile
├── tools/                   # Dev tooling, emulator scripts
├── .github/
│   ├── workflows/           # CI/CD pipelines
│   ├── ISSUE_TEMPLATE/      # Bug report, feature request templates
│   └── PULL_REQUEST_TEMPLATE.md
├── .gitignore
├── .editorconfig
├── CHANGELOG.md
├── CODE_OF_CONDUCT.md
├── CONTRIBUTING.md
├── LICENSE
├── Makefile                 # Top-level orchestration
├── README.md
└── SECURITY.md
```

### Signer Modules

| Module | Responsibility |
|--------|---------------|
| `core` | Chain-agnostic crypto interfaces, hashing, HMAC, PBKDF2, key derivation abstractions |
| `chains/bitcoin` | secp256k1, BIP32/39/44/84, BIP173, BIP174 PSBT, Bitcoin-specific signing |
| `wallet` | Key management, account model, address derivation, network selection (mainnet/testnet) |
| `transport` | QR encode/decode, multi-frame protocol, OBEX/MicroSD/text fallback handlers |
| `storage` | Encrypted RecordStore, PIN-derived encryption, secure deletion |
| `ui` | LCDUI screens: onboarding, PIN entry, transaction review, QR display/scan |

### Companion Architecture

All companion apps share a common Rust core library (`companion/core`) built on `rust-bitcoin` and BDK, exposed via FFI/WASM depending on the target:

| App | Stack | Distribution |
|-----|-------|-------------|
| **TUI** | Rust + ratatui | Single binary |
| **Desktop** | Electron + Rust (via native module) | macOS, Linux, Windows installers |
| **Web** | Next.js + WASM (Rust core compiled to wasm) | Hosted + landing page |
| **Extension** | Chrome Extension + WASM | Chrome Web Store |
| **Mobile** | React Native + Rust (via JSI bridge) | App Store, Google Play |

### Standards

| Standard | Usage |
|----------|-------|
| BIP32 | HD key derivation |
| BIP39 | Mnemonic seed phrases + optional passphrase |
| BIP44/84 | Derivation paths (legacy + native SegWit) |
| BIP173 | Bech32 address encoding |
| BIP174 | Partially Signed Bitcoin Transactions (PSBT) |

## Tech Stack

### Signer

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Runtime | Java ME (CLDC 1.1 / MIDP 2.0) | Only runtime available on Nokia C1-01 |
| UI | LCDUI | Native Series 40 UI toolkit — smallest footprint, most reliable |
| Build | Apache Ant | Standard Java ME build tool with JAD/JAR packaging |
| Crypto | Bouncy Castle Lightweight API (J2ME) | Audited, widely used, J2ME-compatible subset |

### Companion Core

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Language | Rust | Best Bitcoin library ecosystem, memory safety, single-binary TUI, WASM target |
| Bitcoin | rust-bitcoin + BDK | Production-grade, well-maintained, full PSBT/descriptor support |
| TUI framework | ratatui | De facto Rust TUI standard |
| WASM target | wasm-bindgen | Shares Rust core with web + extension companions |

### Companion Frontends

| App | Framework | Key Dependencies |
|-----|-----------|-----------------|
| Desktop | Electron | Rust native module via napi-rs |
| Web | Next.js (React) | WASM companion core, webcam QR scanning |
| Extension | Chrome Extension (React) | WASM companion core |
| Mobile | React Native | Rust via JSI bridge, camera QR scanning |

### Protocol

| Component | Choice |
|-----------|--------|
| Transport encoding | PSBT (binary) → Base43/Base64 → QR |
| Test vectors | BIP32/39/173/174 reference vectors |

## Prerequisites

### Signer Development

- **JDK 8** (J2ME tooling requires legacy JDK)
- **Java ME SDK 3.0+** or **Sun WTK 2.5.2** (Wireless Toolkit)
- **Apache Ant 1.10+**
- **Nokia Series 40 SDK** (for accurate emulator testing)

### Companion Development

- **Rust 1.75+** (stable toolchain)
- **wasm-pack** (for WASM targets)
- **Node.js 20 LTS+** (for web, desktop, extension, mobile)
- **pnpm 9+** (package manager for JS workspaces)
- Platform-specific:
  - **Desktop:** Electron build tools
  - **Mobile:** Xcode (iOS) / Android Studio (Android)
  - **Extension:** Chrome browser for testing

## Getting Started (Build from Source)

> If you just want to use Burner Wallet without compiling, see [Installation (Pre-built Releases)](#installation-pre-built-releases) above.

### Clone the Repository

```bash
git clone https://github.com/GuilhermeVozniak/burner-wallet.git
cd burner-wallet
```

### Set Up Development Tools

```bash
# Downloads ProGuard, Checkstyle, and builds the FreeJ2ME-Plus J2ME emulator
make setup-tools
```

### Build the Signer (MIDlet)

```bash
cd signer

# Set environment variables for your J2ME SDK location
export WTK_HOME=/path/to/java-me-sdk
export JAVA_HOME=/path/to/jdk8

# Build the MIDlet JAR + JAD
ant clean build

# Run in emulator
ant emulator
```

### Run the Signer in the Emulator

If you don't have a Nokia phone, run the signer in the FreeJ2ME-Plus emulator:

```bash
# From the repository root (requires make setup-tools + make signer)
make emulator
```

This opens a graphical window emulating a Nokia C1-01. Use your keyboard to interact with the phone's keypad.

### Build the Companion (TUI)

```bash
cd companion/tui

# Build release binary
cargo build --release

# Run
./target/release/burner-companion
```

### Build the Companion (Web)

```bash
cd companion/web

# Install dependencies
pnpm install

# Build WASM core
pnpm run build:wasm

# Start dev server
pnpm dev
```

### Deploy Signer to Device

```bash
# Package for device transfer
cd signer && ant package

# Transfer signer.jar + signer.jad to Nokia C1-01 via:
# - Bluetooth OBEX push
# - USB mass storage mode
# - MicroSD card
```

## Development Workflow

### Branching Strategy

We use **GitHub Flow**:

1. Create a feature branch from `main`: `feat/bip39-mnemonic`
2. Make changes, commit using [Conventional Commits](#commit-conventions)
3. Open a pull request against `main`
4. Pass CI checks + code review
5. Squash merge into `main`

Branch naming:
- `feat/<description>` — New features
- `fix/<description>` — Bug fixes
- `docs/<description>` — Documentation
- `chore/<description>` — Tooling, CI, dependencies
- `security/<description>` — Security-related changes

### Commit Conventions

This project follows [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(signer): implement BIP39 mnemonic generation
feat(companion-tui): add UTXO selection interface
fix(crypto): correct point multiplication for edge case
docs(spec): add PSBT transport encoding detail
test(vectors): add BIP32 derivation test suite
chore(ci): configure Java ME SDK in build container
security(storage): harden key derivation parameters
```

Valid scopes: `signer`, `companion-tui`, `companion-web`, `companion-desktop`, `companion-mobile`, `companion-extension`, `companion-core`, `protocol`, `crypto`, `storage`, `transport`, `ci`, `docs`, `spec`.

### DCO Sign-Off

All commits must include a `Signed-off-by` trailer certifying the [Developer Certificate of Origin](https://developercertificate.org/):

```bash
git commit -s -m "feat(signer): implement BIP39 mnemonic generation"
# Adds: Signed-off-by: Your Name <your@email.com>
```

### Code Style

**Java ME (Signer):**
- Standard Java conventions adapted for J2ME constraints
- No Java 5+ features (no generics, no autoboxing, no enhanced for-loop)
- All classes must fit within CLDC 1.1 class library
- Checkstyle config at `signer/.checkstyle.xml`

**Rust (Companion Core + TUI):**
- `rustfmt` for formatting, `clippy` for linting (both enforced in CI)
- Follow Rust API Guidelines

**TypeScript (Web, Desktop, Extension, Mobile):**
- ESLint + Prettier, config at companion workspace root
- Strict TypeScript (`strict: true`)

### Pre-commit Checks

```bash
# Top-level orchestration
make lint          # Static analysis across all projects
make test          # All test suites
make size-check    # Signer JAR size budget

# Per-project
cd signer && ant check
cd companion/core && cargo clippy && cargo test
cd companion/web && pnpm lint && pnpm test
```

## Testing

### Test Strategy

| Level | Signer | Companion |
|-------|--------|-----------|
| **Unit** | Crypto primitives vs BIP test vectors | Bitcoin protocol logic, fee estimation, PSBT construction |
| **Integration** | PSBT sign → verify round-trip in emulator | UTXO fetch → PSBT build → broadcast (testnet) |
| **E2E** | Full UX flow on Nokia C1-01 | QR scan round-trip: companion ↔ signer |
| **Size budget** | JAR under device limit | N/A |
| **Security** | Deterministic signing, entropy quality | Input validation, WASM boundary safety |

### Running Tests

```bash
# Everything
make test

# Signer only
cd signer && ant test

# Companion core (Rust)
cd companion/core && cargo test

# Companion web
cd companion/web && pnpm test

# Crypto vectors only
cd signer && ant test-crypto

# Size budget check
cd signer && ant size-check
```

### Critical Test Vectors

The following **must pass** before any release:

- [ ] BIP39 English wordlist mnemonic generation + validation
- [ ] BIP39 mnemonic → seed derivation (all TREZOR test vectors)
- [ ] BIP39 passphrase → different seed derivation
- [ ] BIP32 master key derivation from seed
- [ ] BIP32 child key derivation (test vectors 1–3)
- [ ] BIP44/84 path derivation for Bitcoin mainnet and testnet
- [ ] BIP173 bech32 address encoding/decoding
- [ ] BIP174 PSBT parsing, signing, and finalization
- [ ] secp256k1 scalar multiplication (edge cases: zero, order, generator multiples)
- [ ] ECDSA signing produces valid DER-encoded signatures
- [ ] Deterministic signing (RFC 6979)
- [ ] Network toggle produces correct address prefixes (mainnet vs testnet)

## CI/CD

### GitHub Actions Workflows

| Workflow | Trigger | Runners | Steps |
|----------|---------|---------|-------|
| `ci-signer` | PR, push to `main` | Linux | Compile (Ant + J2ME SDK) → Test vectors → JAR size check → Checkstyle/PMD |
| `ci-companion` | PR, push to `main` | Linux + macOS + Windows | Rust: clippy + test → WASM build → Web: lint + test → Desktop: build → Mobile: build |
| `release` | Git tag `v*` | Linux + macOS + Windows | Full build all targets → Sign artifacts → GitHub Release → Checksums |

### Creating a Release

Releases are built automatically by GitHub Actions when a version tag is pushed via git.

```bash
# 1. Ensure you're on main with all changes merged
git checkout main && git pull origin main

# 2. Create and push the tag
git tag v0.x.y
git push origin v0.x.y
```

The workflow builds the signer JAR, all companion TUI binaries, and the web bundle, then creates a **draft release** with all artifacts attached.

```bash
# 3. Publish the draft release
gh release edit v0.x.y --draft=false --latest
```

Or go to **Releases** on GitHub, find the draft, click **Edit**, then **Publish release**.

> **Important:** Do not create releases or tags through the GitHub web UI — the release workflow may not trigger. Always push tags via `git push`. If a tag push fails to trigger the workflow, you can run it manually from **Actions > Release > Run workflow** (requires the `workflow_dispatch` trigger).

### Release Artifacts

Each release publishes:

**Signer:**
- `burner-wallet-signer-<version>.jar` — MIDlet application
- `burner-wallet-signer-<version>.jad` — MIDlet descriptor

**Companion:**
- `burner-companion-tui-<version>-{linux,macos,windows}` — TUI binaries
- `burner-companion-desktop-<version>-{dmg,AppImage,exe}` — Desktop installers
- `burner-companion-web-<version>.tar.gz` — Self-hostable web build

**Verification:**
- `SHA256SUMS.txt` — Checksums for all artifacts
- Source tarball for reproducible builds

## Versioning

This project uses [Semantic Versioning](https://semver.org/):

- **Pre-1.0** (`0.x.y`): API, storage format, and protocol may change between minor versions
- **Post-1.0** (`x.y.z`): Breaking changes increment major version

Signer and companion are versioned together (monorepo single version). All notable changes are documented in [CHANGELOG.md](CHANGELOG.md).

## Roadmap

| Milestone | Scope |
|-----------|-------|
| **M0 — Emulator PoC** | Seed generation, address derivation, basic LCDUI — runs in J2ME emulator |
| **M1 — Device Signer** | Full signer on Nokia C1-01: encrypted storage, PIN, passphrase, QR display + scan |
| **M2 — Companion + E2E** | TUI companion (Rust): UTXO sync, PSBT construction, QR scan, broadcast. Full air-gapped loop |
| **M3 — Multi-platform** | Electron desktop, Next.js web + landing page, Chrome extension, React Native mobile |
| **M4 — Security Hardening** | Independent review, deterministic builds, entropy audit, mainnet confidence |
| **M5 — Multi-chain** | First additional chain (e.g., Ethereum or Liquid) using the extensible module architecture |

### Release Gates (Before Mainnet Confidence)

No release will be promoted as mainnet-ready until:

- [ ] All BIP test vectors pass on target device
- [ ] Crypto implementation reviewed by independent party
- [ ] Deterministic build verified by at least 2 independent parties
- [ ] Secure deletion behavior validated on target hardware
- [ ] Recovery flow tested end-to-end (seed backup → device wipe → restore → sign)
- [ ] Threat model published and reviewed
- [ ] Testnet soak period completed without issues

## Security

**This software is pre-alpha. Use at your own risk. The authors recommend testnet-only until the project reaches mainnet-ready status.**

### Threat Model

| Threat | Status | Mitigation |
|--------|--------|------------|
| Compromised companion device | In scope | Signer never trusts companion beyond PSBT structure; all signing keys remain on signer |
| Physical device theft | In scope | PIN + encrypted storage; optional BIP39 passphrase for plausible deniability |
| Supply-chain attack on JAR | In scope | Reproducible builds + SHA256 checksums + signed releases |
| Side-channel attacks | In scope | Constant-time operations where J2ME allows; documented limitations |
| Phishing / social engineering | Out of scope | User responsibility |

### Reporting Vulnerabilities

**Do not open public issues for security vulnerabilities.**

This project uses [GitHub Private Vulnerability Reporting](https://github.com/GuilhermeVozniak/burner-wallet/security/advisories/new). To report a vulnerability:

1. Go to the **Security** tab of this repository
2. Click **Report a vulnerability**
3. Fill in the details

We will acknowledge receipt within 48 hours and provide a timeline for resolution.

See [SECURITY.md](SECURITY.md) for full details.

## Contributing

Contributions are welcome. This is an early-stage project with significant work across many surfaces.

### How to Contribute

1. Check [open issues](https://github.com/GuilhermeVozniak/burner-wallet/issues) for tasks marked `good first issue` or `help wanted`
2. Read [CONTRIBUTING.md](CONTRIBUTING.md) for the full guide
3. Fork the repo, create a feature branch, submit a PR
4. All commits must be signed off ([DCO](#dco-sign-off))

### Areas Where Help Is Needed

- **Java ME crypto** — Porting/adapting Bouncy Castle lightweight API for CLDC 1.1
- **Rust companion core** — rust-bitcoin/BDK integration, PSBT construction, WASM compilation
- **J2ME testing** — Emulator automation, CI integration for Java ME builds
- **QR protocol** — Multi-frame encoding strategy for larger PSBTs
- **Electron/React Native** — Desktop and mobile companion frontends
- **Next.js landing page** — Project website and web companion UI
- **Chrome extension** — Extension companion for browser-based workflows
- **Security review** — Threat modeling, crypto review, side-channel analysis
- **Device testing** — Nokia C1-01 and other Series 40 devices
- **Multi-chain architecture** — Designing chain-agnostic interfaces for future extensibility

## Project Documentation

| Document | Description | Status |
|----------|-------------|--------|
| [Project Plan](docs/javame-bitcoin-wallet-plan.md) | Master planning document | Draft |
| [PRD](docs/prd-airgapped-bitcoin-javame.md) | Product requirements | Planned |
| [Technical Spec](docs/spec-airgapped-architecture.md) | Architecture & component design | Planned |
| [Security Profile](docs/security-crypto-profile.md) | Crypto standards & hardening | Planned |
| [Scaffold Blueprint](docs/scaffold-repo-layout.md) | Repository layout specification | Planned |
| [Boilerplate Survey](docs/boilerplate-options-javame.md) | Java ME tooling comparison | Planned |

## Environment Variables

### Signer Build

| Variable | Description | Required |
|----------|-------------|----------|
| `WTK_HOME` | Path to Java ME SDK / Wireless Toolkit | Yes |
| `JAVA_HOME` | Path to JDK 8 | Yes |

### Companion

| Variable | Description | Required |
|----------|-------------|----------|
| `BITCOIN_NETWORK` | `mainnet`, `testnet`, or `signet` | No (default: `testnet`) |
| `BITCOIN_RPC_URL` | Bitcoin node RPC endpoint | For self-hosted node |
| `ELECTRUM_URL` | Electrum server URL | Alternative to RPC |

## License

[MIT](LICENSE) — Copyright (c) 2025 Guilherme Vozniak

## Acknowledgments

- [Bitcoin Improvement Proposals](https://github.com/bitcoin/bips) — The BIP standards this project implements
- [Bouncy Castle](https://www.bouncycastle.org/) — Crypto library foundation
- [rust-bitcoin](https://github.com/rust-bitcoin/rust-bitcoin) + [BDK](https://bitcoindevkit.org/) — Companion core Bitcoin libraries
- The air-gapped wallet community ([SeedSigner](https://seedsigner.com/), [Krux](https://selfcustody.github.io/krux/), [Passport](https://foundationdevices.com/)) for proving the concept

## FAQ

**Q: Why a Nokia C1-01?**
A: It's a phone with a camera, a screen, a keypad, and no Wi-Fi or smartphone OS. It runs Java ME, which provides a sandboxed execution environment. The hardware enforces the air gap — no amount of software compromise can make it connect to the internet.

**Q: Can I use a different Series 40 phone?**
A: Yes. Any Series 40 device with CLDC 1.1 / MIDP 2.0 and a camera should work. The Nokia C1-01 is the primary test target.

**Q: Is this safe for real Bitcoin?**
A: Not yet. The project is pre-alpha. Use testnet only until the project reaches mainnet-ready status (see [Release Gates](#release-gates-before-mainnet-confidence)).

**Q: Why not just use a Raspberry Pi Zero like SeedSigner?**
A: Different threat model. The Pi has USB, HDMI, GPIO, and runs a full Linux kernel. A Nokia feature phone has a dramatically smaller attack surface and a sandboxed Java ME runtime. It's also cheaper and more widely available globally.

**Q: Will this support Ethereum / other chains?**
A: The architecture is being designed with multi-chain extensibility from day one. Bitcoin is the first and primary chain. Additional chains will follow in milestone M5.

**Q: How do I get a Nokia C1-01?**
A: Used units are widely available on eBay, local classifieds, and electronics recyclers for a few dollars. Ensure the device is unlocked and functional.

**Q: Do I need to compile the code myself?**
A: No. Pre-built binaries for the signer (JAR) and companion (macOS, Linux, Windows) are available on the [GitHub Releases](https://github.com/GuilhermeVozniak/burner-wallet/releases) page. See [Installation (Pre-built Releases)](#installation-pre-built-releases) for step-by-step instructions. Building from source is only needed for development or if you want to verify the build yourself.

**Q: Can I test without a Nokia phone?**
A: Yes. Build from source and run `make emulator` to launch the signer in the FreeJ2ME-Plus J2ME emulator on your computer. See [Getting Started (Build from Source)](#getting-started-build-from-source).

---

<p align="center">
  <strong>Burner Wallet</strong> — Your old Nokia is your new cold storage.
</p>
