# M1b — Signer Storage & UI Design

## Goal

Turn the Java ME signer from a crypto library into a usable wallet on the Nokia C1-01. Users can generate or import a BIP39 mnemonic, protect it with a PIN, and view receive addresses — all offline.

## Architecture

Single-RecordStore encrypted storage with Form-based LCDUI screens. No Canvas rendering (deferred to M1c for QR codes). PIN-derived AES-256-CBC encryption via Bouncy Castle `AESLightEngine`. Entropy from keypad timing + system time, mixed with SHA-256.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Onboarding | Generate + Import | User can create fresh or restore from backup |
| PIN policy | 4-8 digits, no lockout | Cold storage rarely unlocked; lockout risks locking user out of funds |
| Entropy | Keypad timing + system time | Simple, transparent, no extra deps |
| Passphrase | Included in M1b | Enables plausible deniability |
| Address display | Text only | QR deferred to M1c transport layer |
| Storage | Single RecordStore, fixed IDs | ~154 bytes total, well within S40 limits |
| UI framework | Form-based LCDUI | Native S40 look, minimal code |
| AES engine | AESLightEngine | Smallest ProGuard footprint, lowest memory |
| PBKDF2 iterations | 5000 | ~5-10s on 208 MHz ARM, acceptable for cold storage |

---

## Storage Layer

### New modules

```
core/AesUtils.java           — AES-256-CBC encrypt/decrypt (BC AESLightEngine)
core/EntropyCollector.java   — keypad timing + System.currentTimeMillis() → SHA-256
storage/WalletStore.java     — RecordStore CRUD wrapper
storage/WalletData.java      — value type for serialized blob fields
storage/RecordStoreAdapter.java — interface for RMS abstraction (testability)
storage/MidpRecordStoreAdapter.java — production impl using javax.microedition.rms
```

### RecordStore layout

Store name: `"bw"`. Three fixed records:

| Record ID | Content | Format | Size |
|-----------|---------|--------|------|
| 1 | Encrypted seed blob | `salt(16) + iv(16) + iterations(4 BE) + ciphertext(80)` | 116 B |
| 2 | PIN verification hash | `SHA-256(derived_aes_key)` | 32 B |
| 3 | Config | `network(1) + hasPassphrase(1) + addressIndex(4 BE)` | 6 B |

Total: ~154 bytes.

### Encryption flow (wallet creation)

1. User enters PIN (4-8 digits)
2. Generate 16-byte salt from `EntropyCollector`
3. `aesKey = PBKDF2-HMAC-SHA512(pin_bytes, salt, 5000, 32)`
4. Generate 16-byte IV from `EntropyCollector`
5. Plaintext = `seed(64) + passphrase_utf8_length(2 BE) + passphrase_utf8(0-N)` padded to block boundary by AES-CBC PKCS7
6. `ciphertext = AES-256-CBC-encrypt(plaintext, aesKey, iv)`
7. Store `SHA-256(aesKey)` in record 2
8. Zero-fill all key material arrays

### Decryption flow (unlock)

1. User enters PIN
2. Load salt, IV, iterations, ciphertext from record 1
3. `aesKey = PBKDF2-HMAC-SHA512(pin_bytes, salt, iterations, 32)`
4. Compare `SHA-256(aesKey)` with record 2 — fast wrong-PIN rejection
5. If match: `plaintext = AES-256-CBC-decrypt(ciphertext, aesKey, iv)`
6. Extract seed (first 64 bytes) and passphrase (length-prefixed remainder)
7. Zero-fill key material after use

---

## UI Flow

### Screen map

```
[App Launch]
     |
     v
[Wallet exists?] --no--> [Welcome]
     |                     "Create" / "Import"
    yes                        |
     |                    [Generate] or [Import]
     v                         |
[PIN Entry]              [Passphrase (optional)]
     |                         |
     v                    [Set PIN] → [Confirm PIN]
[Wallet Home]                  |
  "Receive"              [Encrypting...]
  "Settings"                   |
     |                    [PIN Entry → Wallet Home]
     v
[Receive Address]
  bc1q... (text)
  "Next" index
     |
[Settings]
  Network toggle
  Wipe Wallet
  About
```

### Screen classes

```
ui/
  BurnerWalletMIDlet.java    — lifecycle, Display singleton
  ScreenManager.java         — screen transitions, alert helpers
  PinScreen.java             — entry, creation, confirmation
  OnboardingScreen.java      — welcome, generate, import, passphrase
  WalletHomeScreen.java      — main menu
  ReceiveScreen.java         — address display with index nav
  SettingsScreen.java        — network, wipe, about
```

### Screen details

**PIN Entry**: `Form` with `TextField("PIN:", "", 8, NUMERIC|PASSWORD)`. OK verifies, Exit quits. Wrong PIN shows `Alert(ERROR, 2s)`.

**Welcome**: `List(IMPLICIT)` — "Create New Wallet", "Import Existing Wallet".

**Generate flow**:
1. Entropy collection — prompt user to "press random keys" on keypad. `EntropyCollector` records timing deltas for 32+ keypresses.
2. Generate 12-word mnemonic from 128-bit entropy via `Bip39Mnemonic.generate()`.
3. Display words 4 at a time across 3 `Form` screens (Words 1-4, 5-8, 9-12).
4. Verify: ask user to enter word at a random position.

**Import flow**:
1. Choose word count: 12 or 24.
2. Per-word `Form` with `TextField`. Enter all words sequentially.
3. Validate checksum via `Bip39Mnemonic.validate()`.
4. On invalid: `Alert(ERROR)` → retry.

**Passphrase**: `Form` with `TextField("Passphrase:", "", 64, ANY|SENSITIVE)`. OK sets it, Skip uses empty.

**Set PIN**: `Form` → enter PIN → confirm PIN. Mismatch shows `Alert(ERROR)` → retry.

**Wallet Home**: `List(IMPLICIT)` — "Receive Address", "Settings".

**Receive Address**: `Form` with `StringItem` showing bech32 address (split across lines) and derivation path. "Next" soft key increments address index, "Back" returns to home.

**Settings**: `List(IMPLICIT)` — "Network: Mainnet" (toggle), "Wipe Wallet" (confirm alert → delete RecordStore), "About".

---

## Entropy Collection

`EntropyCollector` extends `Canvas` to capture raw keypad events:

1. Display "Press random keys..." message
2. On each `keyPressed()`: record `System.currentTimeMillis()` delta from previous press
3. After 32+ keypresses: concatenate all timing deltas + absolute timestamps
4. `entropy = SHA-256(collected_bytes)` → 32 bytes
5. Use first 16 bytes for mnemonic entropy (128-bit = 12 words)
6. Remaining 16 bytes reserved for salt/IV generation

---

## Testing Strategy

### Unit tests (JUnit on JDK 8)

| Test class | Coverage |
|-----------|----------|
| `AesUtilsTest` | Encrypt/decrypt round-trip, wrong key, known AES-CBC vectors, padding |
| `EntropyCollectorTest` | Output length, uniqueness, SHA-256 mixing |
| `WalletStoreTest` | Save/load round-trip, PIN verify (correct + wrong), config CRUD, wipe |
| `WalletDataTest` | Blob serialization/deserialization |
| `WalletIntegrationTest` | Full: generate → encrypt → store → load → decrypt → derive address |

### RMS abstraction

`RecordStoreAdapter` interface allows tests to use `InMemoryRecordStoreAdapter` (HashMap-based) instead of real MIDP RecordStore.

```java
public interface RecordStoreAdapter {
    void open(String name, boolean create) throws Exception;
    byte[] getRecord(int id) throws Exception;
    int addRecord(byte[] data) throws Exception;
    void setRecord(int id, byte[] data) throws Exception;
    void deleteStore() throws Exception;
    int getNumRecords() throws Exception;
    void close() throws Exception;
}
```

### Manual testing

FreeJ2ME-Plus emulator verification of all UI flows:
- Onboarding: generate, show words, verify word, set PIN
- Onboarding: import, enter words, validate, set PIN
- Unlock: correct PIN, wrong PIN
- Receive: view address, navigate index
- Settings: toggle network, wipe wallet

---

## Files to create

| File | Purpose |
|------|---------|
| `signer/src/org/burnerwallet/core/AesUtils.java` | AES-256-CBC encrypt/decrypt |
| `signer/src/org/burnerwallet/core/EntropyCollector.java` | Keypad timing entropy |
| `signer/src/org/burnerwallet/storage/RecordStoreAdapter.java` | RMS interface |
| `signer/src/org/burnerwallet/storage/MidpRecordStoreAdapter.java` | Production RMS impl |
| `signer/src/org/burnerwallet/storage/WalletData.java` | Blob value type |
| `signer/src/org/burnerwallet/storage/WalletStore.java` | Storage CRUD |
| `signer/src/org/burnerwallet/ui/ScreenManager.java` | Screen transitions |
| `signer/src/org/burnerwallet/ui/PinScreen.java` | PIN entry/create/confirm |
| `signer/src/org/burnerwallet/ui/OnboardingScreen.java` | Welcome + generate + import |
| `signer/src/org/burnerwallet/ui/WalletHomeScreen.java` | Main menu |
| `signer/src/org/burnerwallet/ui/ReceiveScreen.java` | Address display |
| `signer/src/org/burnerwallet/ui/SettingsScreen.java` | Config + wipe |
| `signer/test/org/burnerwallet/core/AesUtilsTest.java` | AES tests |
| `signer/test/org/burnerwallet/core/EntropyCollectorTest.java` | Entropy tests |
| `signer/test/org/burnerwallet/storage/WalletStoreTest.java` | Storage tests |
| `signer/test/org/burnerwallet/storage/WalletDataTest.java` | Blob format tests |
| `signer/test/org/burnerwallet/storage/WalletIntegrationTest.java` | End-to-end test |

## Files to modify

| File | Change |
|------|--------|
| `signer/src/org/burnerwallet/ui/BurnerWalletMIDlet.java` | Replace scaffold with real lifecycle |
| `signer/build.xml` | Ensure `storage/` package is included in compile |

## Risk areas

| Risk | Mitigation |
|------|------------|
| PBKDF2 too slow (>15s) | Reduce to 2048 iterations; consider SHA-256 variant |
| RecordStore size limit hit | Total is ~154 bytes; S40 allows 30-50KB minimum |
| EntropyCollector insufficient randomness | 32 keypress timings × ~10 bits each = ~320 bits pre-mixing |
| JAR size increase from AES + UI | Currently 242KB; AESLightEngine adds ~5KB; UI classes ~10KB |
| LCDUI TextField password mode quirks on S40 | Test on FreeJ2ME-Plus; fallback to Canvas if needed |
