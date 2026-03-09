# Threat Model -- Burner Wallet

Version: 1.0 (Pre-alpha)
Last updated: 2026-03-09

This document describes the security architecture, threat actors, attack surfaces, and mitigations for the Burner Wallet project. It is intended for contributors, security reviewers, and anyone evaluating the project for personal use.

---

## 1. System Overview

Burner Wallet is an air-gapped Bitcoin cold-storage system composed of two principals:

**Signer** -- A Nokia C1-01 feature phone running a Java ME MIDlet (CLDC 1.1 / MIDP 2.0). The signer generates keys, stores encrypted seeds, derives addresses, and signs PSBTs. It has no Wi-Fi, no IP stack, and no app store. The hardware enforces the air gap.

**Companion** -- A multi-platform online application (TUI, desktop, web, Chrome extension, mobile) built on a shared Rust core library. The companion syncs UTXOs via Esplora, constructs unsigned PSBTs, receives signed PSBTs from the signer, finalizes them, and broadcasts transactions to the Bitcoin network.

**Air gap** -- Data crosses between companion and signer exclusively via physical-layer transports: QR codes (primary), Bluetooth OBEX file push, MicroSD sneakernet, or manual text entry. No transport involves a bidirectional data channel or IP networking.

### Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Signer <-> Air Gap | The signer trusts nothing from the companion except the structure of unsigned PSBTs. It validates BIP174 format, displays transaction details for user confirmation, and signs only after explicit approval. |
| Companion <-> Bitcoin Network | The companion trusts the Esplora server for UTXO data and fee estimates, but never handles private keys. A compromised Esplora can lie about balances or withhold transactions, but cannot steal funds. |
| User <-> Signer | The user authenticates to the signer via a numeric PIN. The signer trusts the PIN holder to be the wallet owner. |
| User <-> Companion | The companion does not hold secrets. No authentication is required beyond OS-level access to the device running the companion. |

### Data Flow

```
Companion                          Signer
   |                                  |
   |--- unsigned PSBT (QR/BT/SD) --->|
   |                                  |-- user reviews tx on screen
   |                                  |-- user enters PIN to unlock seed
   |                                  |-- signer signs PSBT
   |<--- signed PSBT (QR/BT/SD) -----|
   |                                  |
   |-- finalize + broadcast --------->| Bitcoin Network
```

---

## 2. Assets

| Asset | Location | Sensitivity | Notes |
|-------|----------|-------------|-------|
| BIP39 mnemonic seed | Generated on signer, shown once during backup, then only stored encrypted | CRITICAL | 12 or 24 words. Loss = loss of funds. Exposure = theft of funds. |
| 64-byte BIP39 seed | Signer encrypted storage only | CRITICAL | Derived from mnemonic + optional passphrase. Encrypted at rest with AES-256-CBC. |
| BIP39 passphrase | Signer encrypted storage only | HIGH | Optional "25th word." Stored alongside seed in encrypted blob. Enables plausible deniability. |
| BIP32 private keys | Derived in memory on signer, never persisted | CRITICAL | Derived on-demand from seed during signing. Wiped (zero-filled) after use. |
| PIN | Never stored; derived to AES-256 key via PBKDF2 | HIGH | SHA-256 hash of the derived key is stored for verification. PIN itself is not recoverable from storage. |
| Unsigned PSBTs | Cross the air gap (companion to signer) | LOW | Contain no secrets. An attacker who modifies an unsigned PSBT can only cause the user to sign an unintended transaction -- mitigated by on-screen review. |
| Signed PSBTs | Cross the air gap (signer to companion) | MEDIUM | Contain ECDSA signatures but no private keys. Exposure allows broadcasting the transaction but cannot be used to sign new transactions. |
| UTXO set / balance | Companion only (fetched from Esplora) | LOW | Privacy-relevant (reveals wallet balance and transaction history) but not a theft vector. |
| Wallet descriptor / xpub | Companion only | MEDIUM | Reveals all current and future addresses. Privacy impact but not a direct theft vector. |

---

## 3. Threat Actors

### 3.1 Remote Attacker (Compromised Companion)

**Profile:** An attacker who has gained code execution on the device running the companion app -- through malware, supply chain compromise of a dependency, or exploitation of the companion itself (e.g., XSS in the web companion).

**Capabilities:** Full access to companion memory, storage, and network. Can read xpubs, modify unsigned PSBTs, intercept signed PSBTs, and exfiltrate transaction history. Cannot access the signer or its storage.

### 3.2 Physical Attacker (Stolen Nokia Device)

**Profile:** An attacker who has physical possession of the Nokia C1-01 running the signer MIDlet.

**Capabilities:** Can attempt PIN brute force, dump MIDP RecordStore contents, attempt JTAG/chip-off attacks on flash storage, and analyze the device hardware.

### 3.3 Supply Chain Attacker (Modified JAR or Firmware)

**Profile:** An attacker who compromises the build pipeline, distribution channel, or dependencies to deliver a modified signer JAR or companion binary.

**Capabilities:** Can insert backdoors, weaken cryptography, exfiltrate seeds during generation, or replace addresses during PSBT construction.

### 3.4 Side-Channel Attacker (Timing, Power Analysis)

**Profile:** An attacker with physical proximity to the signer device during cryptographic operations, equipped with timing measurement or electromagnetic/power analysis equipment.

**Capabilities:** Can potentially extract private key material through timing variations in ECDSA signing, power consumption patterns during AES decryption, or EM emissions during key derivation.

---

## 4. Attack Surfaces

### 4.1 Companion Device Compromise

| Attribute | Detail |
|-----------|--------|
| **Vector** | Malware, dependency compromise, or remote code execution on the companion host OS. Attacker gains control of the companion app. |
| **Likelihood** | MEDIUM -- Companion runs on general-purpose devices (laptops, phones, browsers) that face constant attack. |
| **Impact** | HIGH -- Attacker can substitute recipient addresses in unsigned PSBTs, causing the user to unknowingly sign a transaction sending funds to the attacker. Attacker can also exfiltrate xpubs, destroying privacy. |
| **Mitigation** | (1) Signer displays full transaction details (recipient address, amount, fee) for user verification before signing. (2) Private keys never leave the signer. (3) Companion never holds signing material. |
| **Residual risk** | Address substitution attacks succeed if the user does not carefully verify the on-screen address on the signer. Users must compare addresses character-by-character. |

### 4.2 Physical Device Theft

| Attribute | Detail |
|-----------|--------|
| **Vector** | Attacker steals or gains temporary physical access to the Nokia C1-01. |
| **Likelihood** | MEDIUM -- The device is small and portable. |
| **Impact** | CRITICAL if PIN is brute-forced -- full access to seed and all funds. |
| **Mitigation** | (1) Seed encrypted with AES-256-CBC using PBKDF2-HMAC-SHA512 derived key (5000 iterations). (2) PIN verification uses constant-time comparison. (3) BIP39 passphrase provides a second layer -- without it, the attacker gets a decoy wallet. (4) `wipe()` method enables secure deletion. |
| **Residual risk** | PBKDF2 with 5000 iterations is tuned for Nokia hardware (~5-10s per attempt) but may be faster on modern hardware extracting the RecordStore. A 4-digit numeric PIN has only 10,000 combinations. Short PINs are vulnerable to offline brute force if the encrypted blob is extracted. |

### 4.3 QR Transport Channel

| Attribute | Detail |
|-----------|--------|
| **Vector** | Visual eavesdropping (shoulder surfing or camera) on QR codes displayed during PSBT transfer. Injection of malicious QR codes into the signer's camera view. |
| **Likelihood** | LOW -- Requires physical proximity during the brief display window. |
| **Impact** | LOW-MEDIUM -- Eavesdropping on unsigned PSBTs reveals transaction intent but no secrets. Eavesdropping on signed PSBTs allows broadcasting (funds go to intended recipient, not attacker). Injection of a crafted unsigned PSBT could trick the user into signing an unintended transaction. |
| **Mitigation** | (1) QR codes are displayed briefly and only during active transfers. (2) Signed PSBTs contain no private key material. (3) Signer displays transaction details for user confirmation before signing. (4) Multi-frame protocol uses indexed frames with total count, making partial injection detectable. |
| **Residual risk** | A sophisticated attacker could project a replacement QR code onto the signer's camera, substituting the entire unsigned PSBT. The user must verify transaction details on the signer screen. |

### 4.4 Bluetooth OBEX Fallback

| Attribute | Detail |
|-----------|--------|
| **Vector** | Bluetooth sniffing, MITM during OBEX file push, or exploitation of the Nokia's Bluetooth stack. |
| **Likelihood** | LOW -- OBEX is fire-and-forget file transfer with no persistent pairing data channel. Bluetooth range is limited (~10m). |
| **Impact** | MEDIUM -- Same as QR eavesdropping (transaction data, no keys). Bluetooth stack vulnerabilities could potentially achieve code execution on the Nokia. |
| **Mitigation** | (1) OBEX is a fallback, not the default transport. (2) No persistent pairing required. (3) Nokia C1-01 Bluetooth stack is minimal and well-tested over decades. (4) Transferred data contains no private key material. |
| **Residual risk** | Legacy Bluetooth stacks (Bluetooth 2.0 on C1-01) have known vulnerabilities. The Nokia firmware is no longer maintained. Users should prefer QR transport. |

### 4.5 MicroSD Fallback

| Attribute | Detail |
|-----------|--------|
| **Vector** | MicroSD card interception, modification of files on the card between devices, or forensic recovery of deleted PSBT files from the card. |
| **Likelihood** | LOW -- Requires physical access to the card during transfer. |
| **Impact** | LOW -- Same data as QR (unsigned/signed PSBTs, no keys). File remnants on the card could reveal transaction history. |
| **Mitigation** | (1) MicroSD is a fallback transport only. (2) Users should delete PSBT files after transfer. (3) No private keys are ever written to the card. |
| **Residual risk** | Flash storage wear leveling makes secure deletion unreliable. PSBT remnants may be forensically recoverable. Privacy impact only, not a theft vector. |

### 4.6 Manual Text Entry

| Attribute | Detail |
|-----------|--------|
| **Vector** | Transcription errors or social engineering to trick the user into entering a crafted payload. |
| **Likelihood** | VERY LOW -- Manual entry is the last-resort fallback for small payloads. |
| **Impact** | LOW -- A malformed payload will fail PSBT parsing. A valid but malicious payload is caught by transaction review on the signer screen. |
| **Mitigation** | (1) PSBT parser validates structure and rejects malformed input. (2) User reviews transaction details before signing. (3) Manual entry is impractical for large or complex payloads, limiting the attack surface. |
| **Residual risk** | Minimal. Entry errors produce parse failures, not security failures. |

### 4.7 Signer JAR Supply Chain

| Attribute | Detail |
|-----------|--------|
| **Vector** | Attacker compromises the build pipeline, CI server, or distribution channel to deliver a trojanized signer JAR. Alternatively, attacker modifies the Bouncy Castle dependency or ProGuard configuration. |
| **Likelihood** | LOW-MEDIUM -- Supply chain attacks are increasingly common. The signer uses a third-party crypto library (Bouncy Castle) and a bytecode optimizer (ProGuard). |
| **Impact** | CRITICAL -- A backdoored JAR could exfiltrate the seed through a covert channel (e.g., encoding key bits in QR code padding, biased nonce generation enabling key recovery). |
| **Mitigation** | (1) Reproducible/deterministic builds enable independent verification. (2) SHA256 checksums published for all release artifacts. (3) ProGuard config is version-controlled and auditable. (4) CI builds run on GitHub-hosted runners with pinned dependency versions. (5) Bouncy Castle `bcprov-jdk14` JAR is committed to the repository (not fetched at build time). |
| **Residual risk** | Reproducible builds are a goal but not yet independently verified. Until at least 2 independent parties confirm build reproducibility, users must trust the release artifacts. |

### 4.8 Key Generation Entropy

| Attribute | Detail |
|-----------|--------|
| **Vector** | Insufficient entropy during mnemonic generation, leading to predictable seeds. The Nokia C1-01 has no `/dev/urandom` or hardware RNG -- entropy comes from `EntropyCollector`, which hashes keypress timing deltas. |
| **Likelihood** | LOW-MEDIUM -- Timing entropy quality depends on user behavior and the resolution of `System.currentTimeMillis()` on the target device. |
| **Impact** | CRITICAL -- Predictable seeds allow an attacker to derive all private keys and steal all funds. |
| **Mitigation** | (1) EntropyCollector requires 32 keypresses and hashes timing deltas with SHA-256 to produce 32 bytes. (2) Millisecond-resolution timing on physical keypresses provides reasonable entropy on real hardware. (3) BIP39 mnemonic checksum provides a structural validity check. |
| **Residual risk** | The entropy source has not been independently audited. `System.currentTimeMillis()` resolution on the Nokia C1-01 may be coarser than 1ms, reducing effective entropy. An emulator or modified firmware could return deterministic timing. No entropy estimation or health check is performed. |

### 4.9 PIN Brute Force

| Attribute | Detail |
|-----------|--------|
| **Vector** | Offline brute force of the PIN after extracting the encrypted seed blob from MIDP RecordStore (via chip-off, JTAG, or filesystem dump). |
| **Likelihood** | MEDIUM -- Requires physical access plus technical skill to extract the RecordStore, but the PIN space is small. |
| **Impact** | CRITICAL -- Successful brute force yields the seed and all funds. |
| **Mitigation** | (1) PBKDF2-HMAC-SHA512 with 5000 iterations adds computational cost per guess. (2) AES-256-CBC encryption of the seed. (3) PIN verification uses constant-time comparison (no timing oracle). (4) BIP39 passphrase adds a second factor that is not stored alongside the PIN-encrypted blob -- without it, the attacker derives a different (decoy) wallet. |
| **Residual risk** | 5000 PBKDF2 iterations is deliberately low to remain feasible on Nokia hardware (~5-10s/attempt). On modern GPUs or ASICs, the iteration count provides minimal protection. A 4-digit numeric PIN (10,000 combinations) can be exhausted in seconds on modern hardware. **Users should use long alphanumeric PINs and always enable a BIP39 passphrase.** The passphrase is the primary defense against offline brute force. |

### 4.10 ECDSA Signing Side Channels

| Attribute | Detail |
|-----------|--------|
| **Vector** | Timing or power analysis of ECDSA signing operations on the Nokia device to recover the private key or per-signature nonce. |
| **Likelihood** | LOW -- Requires specialized equipment and physical proximity during signing. The Nokia's simple hardware may actually make power analysis easier than on modern SoCs. |
| **Impact** | CRITICAL -- Nonce recovery from a single signature reveals the private key (ECDSA nonce reuse or partial nonce leakage). |
| **Mitigation** | (1) RFC 6979 deterministic nonce generation eliminates nonce reuse risk across multiple signatures for the same key. (2) Low-S normalization (BIP 62/146). (3) Bouncy Castle's `ECDSASigner` with `HMacDSAKCalculator` is the signing implementation. (4) Private key bytes are zero-filled immediately after signing. |
| **Residual risk** | Bouncy Castle's lightweight ECDSA implementation is not constant-time. Scalar multiplication timing may leak key bits. Java ME provides no low-level control over CPU caches, branch prediction, or memory access patterns. This is a documented limitation of the J2ME platform. |

### 4.11 Esplora API Trust

| Attribute | Detail |
|-----------|--------|
| **Vector** | The companion relies on an Esplora server for UTXO data, fee estimation, and transaction broadcasting. A malicious or compromised Esplora server can lie about wallet balances, withhold transactions, or censor broadcasts. |
| **Likelihood** | LOW-MEDIUM -- Public Esplora instances (mempool.space, blockstream.info) are high-value targets. Self-hosted instances eliminate this risk. |
| **Impact** | MEDIUM -- Balance lies can cause users to construct incorrect transactions. Fee manipulation can cause overpayment. Broadcast censorship can delay transactions (but the user can rebroadcast via another server). Cannot steal funds. |
| **Mitigation** | (1) The companion can be pointed at any Esplora instance, including a self-hosted one backed by the user's own full node. (2) The Esplora server never sees private keys. (3) PSBT finalization includes a maximum fee rate check (25,000 sat/vB) to limit fee manipulation damage. |
| **Residual risk** | Default configuration uses public Esplora servers. Users who do not run their own node trust a third party for balance accuracy and transaction propagation. Privacy: the Esplora server sees all wallet addresses and can correlate them. |

### 4.12 Web Companion XSS/Injection

| Attribute | Detail |
|-----------|--------|
| **Vector** | Cross-site scripting (XSS), dependency confusion, or prototype pollution in the Next.js web companion. An attacker injecting JavaScript into the web companion can modify PSBTs, exfiltrate xpubs, or replace displayed addresses. |
| **Likelihood** | MEDIUM -- Web applications face a large attack surface. The web companion uses npm dependencies (bip39, @scure/bip32, @noble/hashes, html5-qrcode, qrcode) which are supply chain targets. |
| **Impact** | HIGH -- Same as companion compromise (4.1): address substitution, privacy loss. The web companion additionally handles BIP39/BIP84 derivation in JavaScript, so a compromised page could exfiltrate the xpub or manipulate addresses. |
| **Mitigation** | (1) Next.js provides built-in XSS protection for React components. (2) Content Security Policy headers should restrict script sources. (3) Crypto dependencies (@scure/bip32, @noble/hashes) are from audited, security-focused libraries. (4) The signer remains the final gatekeeper -- users must verify transaction details on the Nokia screen. |
| **Residual risk** | The web companion runs in a browser, which is inherently a high-risk environment. Browser extensions, compromised CDNs, or malicious dependencies could intercept or modify page behavior. The web companion should not be considered equivalent in security to the TUI or desktop companions. |

---

## 5. Mitigations Summary Table

| Threat | Mitigation | Status |
|--------|------------|--------|
| Companion compromise | Signer displays tx details for user verification; keys never leave signer | Implemented |
| Address substitution | On-screen review of recipient address, amount, and fee on signer | Implemented |
| Physical device theft -- encryption | AES-256-CBC with PBKDF2-HMAC-SHA512 key derivation | Implemented |
| Physical device theft -- passphrase | BIP39 passphrase (25th word) for plausible deniability | Implemented |
| Physical device theft -- wipe | `WalletStore.wipe()` for secure deletion | Implemented |
| PIN brute force -- online | Constant-time PIN verification, PBKDF2 cost | Implemented |
| PIN brute force -- offline | BIP39 passphrase as second factor (not stored with encrypted seed) | Implemented |
| PIN brute force -- lockout | Progressive lockout after failed attempts | Not implemented |
| ECDSA nonce reuse | RFC 6979 deterministic nonce generation | Implemented |
| ECDSA low-S | BIP 62/146 low-S normalization | Implemented |
| ECDSA timing side channel | Constant-time scalar multiplication | Not feasible on J2ME |
| Key material in memory | Zero-fill private keys and derived keys after use | Implemented |
| Entropy quality | SHA-256 hashing of 32 keypress timing deltas | Implemented |
| Entropy audit | Independent entropy quality assessment | Not implemented |
| Supply chain -- signer JAR | Reproducible builds + SHA256 checksums | Planned (not yet verified) |
| Supply chain -- dependencies | BC JAR committed to repo; Rust deps version-locked | Implemented |
| Supply chain -- companion web | Audited crypto libs (@noble, @scure); CSP headers | Partially implemented |
| Esplora trust -- balance | User-configurable Esplora URL; self-hosted option | Implemented |
| Esplora trust -- fees | Max fee rate check (25,000 sat/vB) on finalization | Implemented |
| Esplora trust -- privacy | Self-hosted node option | Documented, not enforced |
| QR eavesdropping | Brief display window; no key material in QR payloads | Implemented |
| QR injection | PSBT validation + transaction review on signer | Implemented |
| Multi-frame integrity | Indexed frames with total count in header | Implemented |
| Bluetooth stack exploits | QR is default; BT is opt-in fallback only | By design |
| Web companion XSS | React/Next.js built-in escaping; CSP headers | Partially implemented |
| Web dependency supply chain | Audited, minimal crypto dependencies | Implemented |
| Deterministic cross-impl | Signer and companion produce identical addresses/signatures | Verified via test vectors |

---

## 6. Non-Goals

The following threats are explicitly out of scope for this threat model:

**Phishing and social engineering.** Burner Wallet cannot protect users who are tricked into entering their mnemonic into a fake recovery tool, sending funds to a scammer's address, or installing a trojanized companion. User education is outside the project's scope.

**Nation-state physical attacks.** Adversaries with resources to decap chips, perform advanced fault injection (laser, voltage glitching), or conduct prolonged electromagnetic analysis are beyond the threat model. The Nokia C1-01 is not a tamper-resistant secure element.

**Compromised JVM on the Nokia.** If the Java ME runtime itself (KVM) is backdoored or contains exploitable vulnerabilities, the signer's security guarantees are void. The JVM is part of the Nokia firmware, which is no longer maintained and cannot be patched.

**Compromised Nokia firmware or baseband.** The signer trusts the Nokia's firmware and baseband processor. While the device has no IP networking, a firmware-level backdoor could theoretically exfiltrate data through the cellular modem. This is considered infeasible for the Nokia C1-01 architecture but is not formally verified.

**Multi-user or multi-tenant scenarios.** Burner Wallet assumes a single user per signer device. There is no access control beyond the single PIN.

**Denial of service.** Attacks that prevent the user from signing or broadcasting transactions (e.g., jamming QR camera, blocking Esplora access) are annoying but do not result in fund loss.

---

## 7. Pre-Mainnet Checklist

The following items must be completed before any release is promoted as suitable for mainnet Bitcoin. This list is derived from the README release gates with additional items identified during threat modeling.

| Item | Status | Notes |
|------|--------|-------|
| All BIP test vectors pass on target device (Nokia C1-01) | Pending | Currently verified on emulator and desktop JDK. On-device testing required. |
| Crypto implementation reviewed by independent party | Not started | Covers: secp256k1, ECDSA, BIP32/39/44/84, BIP143 sighash, PSBT signing. |
| Deterministic build verified by at least 2 independent parties | Not started | Signer JAR reproducibility is the priority. |
| Secure deletion behavior validated on target hardware | Not started | MIDP RecordStore deletion semantics on Nokia flash storage are undocumented. |
| Recovery flow tested end-to-end | Pending | seed backup -> device wipe -> restore -> derive addresses -> sign PSBT. |
| Threat model published and reviewed | In progress | This document. Requires external review. |
| Testnet soak period completed without issues | Not started | Target: 3 months of active testnet use with no critical bugs. |
| Entropy quality audit | Not started | Measure `System.currentTimeMillis()` resolution on Nokia C1-01 and estimate bits of entropy per keypress. |
| PIN brute force cost analysis | Not started | Benchmark PBKDF2 performance on modern hardware against extracted RecordStore blob. Document minimum recommended PIN length. |
| Progressive PIN lockout or wipe-after-N-failures | Not implemented | Protects against online brute force on the device itself. |
| Content Security Policy headers for web companion | Not implemented | Required before web companion handles real funds. |
| Dependency audit for web companion npm packages | Not started | Verify all transitive dependencies. |
| WASM boundary safety review | Not started | Ensure no memory safety issues at the Rust-WASM-JS boundary. |

---

## 8. Residual Risks

The following risks cannot be fully mitigated by the current design and are accepted as inherent limitations:

**Non-constant-time ECDSA on J2ME.** Bouncy Castle's lightweight secp256k1 implementation does not guarantee constant-time scalar multiplication. The J2ME platform provides no primitives for constant-time operations, cache-line control, or branch-free arithmetic. An attacker with physical access and timing/power measurement equipment may be able to extract private keys during signing. This is an inherent limitation of using Java ME for cryptographic signing. Mitigation: RFC 6979 eliminates nonce reuse, and the device is designed to be used in private settings.

**PIN weakness against offline brute force.** The PBKDF2 iteration count (5000) is calibrated for Nokia hardware performance, not for resistance against offline attack. An attacker who extracts the encrypted seed blob from flash storage can brute-force a short numeric PIN in minutes on commodity hardware. The BIP39 passphrase is the real defense against this scenario. Users who do not set a passphrase rely solely on the PIN, which provides weak protection against a determined physical attacker.

**Entropy quality is unaudited.** The `EntropyCollector` relies on `System.currentTimeMillis()` timing of keypress events. The resolution and entropy density of this source on the Nokia C1-01 are not empirically measured. If the timer resolution is 10ms rather than 1ms, each keypress contributes roughly 3-4 bits of entropy rather than 7-8, and 32 keypresses may yield only 100-128 bits of entropy rather than 256. This may be sufficient but has not been proven.

**Nokia firmware is end-of-life.** The Nokia C1-01 firmware (including the Java ME runtime, Bluetooth stack, and baseband processor) is no longer maintained by Nokia/HMD. Known or unknown vulnerabilities in the firmware will never be patched. The signer's security depends on the integrity of this unpatched software stack.

**Esplora privacy leakage.** The default companion configuration connects to a public Esplora server, which learns all wallet addresses and can correlate them with the user's IP address. This is a privacy risk, not a theft risk. Users who require privacy must run their own Esplora instance backed by a personal full node.

**QR visual channel is unencrypted and unauthenticated.** PSBTs cross the air gap as plaintext QR codes. An attacker who can observe the QR display or camera view learns the transaction details. There is no encryption or authentication of the QR channel itself -- the signer's transaction review screen is the sole defense against PSBT substitution. A future protocol version could add HMAC-based authentication of QR frames, but this is not currently implemented.

**Flash storage wear leveling defeats secure deletion.** MIDP `RecordStore.deleteRecord()` marks records as deleted, but the underlying flash storage may retain data in spare blocks due to wear leveling. The `WalletStore.wipe()` method cannot guarantee that all copies of the encrypted seed blob are erased from flash. An attacker with chip-off capabilities may recover deleted data.

**Browser environment for web companion.** The web companion runs in a browser, which is a fundamentally adversarial environment. Browser extensions, compromised CDNs, DNS hijacking, and JavaScript supply chain attacks can all modify page behavior. The web companion should be treated as the least-trusted companion variant. Users handling significant funds should prefer the TUI or desktop companion.

---

## Appendix A: Cryptographic Primitives

| Primitive | Implementation | Usage |
|-----------|---------------|-------|
| AES-256-CBC | Bouncy Castle `AESLightEngine` + `CBCBlockCipher` + PKCS7 | Seed encryption at rest |
| PBKDF2-HMAC-SHA512 | Bouncy Castle via `HashUtils` | PIN to AES key derivation |
| SHA-256 | Bouncy Castle | PIN hash verification, entropy mixing, HASH160 |
| RIPEMD-160 | Bouncy Castle | HASH160 (with SHA-256) for address derivation |
| ECDSA (secp256k1) | Bouncy Castle `ECDSASigner` + `HMacDSAKCalculator` | Transaction signing (RFC 6979) |
| HMAC-SHA512 | Bouncy Castle | BIP32 key derivation, BIP39 seed derivation |

## Appendix B: Storage Format

The signer stores three records in the MIDP RecordStore named `"bw"`:

| Record | Contents |
|--------|----------|
| 1 -- Seed blob | `salt (16B) \|\| IV (16B) \|\| iterations (4B) \|\| ciphertext (variable)` |
| 2 -- PIN hash | `SHA-256(PBKDF2_derived_key)` -- 32 bytes |
| 3 -- Config | `network (1B) \|\| hasPassphrase (1B) \|\| addressIndex (4B)` |

The plaintext encrypted in record 1 is: `seed (64B) \|\| passphrase_length (2B) \|\| passphrase (variable)`.
