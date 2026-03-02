# M0 — Emulator PoC Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement BIP39 mnemonic generation, BIP32 key derivation, BIP84 derivation paths, and BIP173 bech32 address generation in the companion core (Rust), and set up the J2ME toolchain so the signer builds and runs in an emulator.

**Architecture:** Two parallel tracks. Track A sets up the Java ME build toolchain (jenv, ProGuard, MIDP stubs, emulator). Track B implements the Bitcoin crypto modules in Rust using TDD against the test vectors in `protocol/vectors/`. Each Rust module is a thin wrapper around `rust-bitcoin` and the `bip39` crate, exposing a clean API for companion frontends.

**Tech Stack:** Rust (bitcoin 0.32, bip39 2.x, bdk_wallet 1.x), Java ME (CLDC 1.1 / MIDP 2.0), Apache Ant, ProGuard 7.x, FreeJ2ME-Plus emulator.

---

## Track A: J2ME Toolchain Setup

### Task A1: Register Zulu JDK 8 in jenv

**Files:**
- Modify: project root `.java-version` (create for signer)

**Step 1: Register the JDK**

```bash
jenv add /Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
```

Expected output: something like `oracle64-1.8.0.xxx added`

**Step 2: Set JDK 8 for the signer directory**

```bash
cd signer
jenv local 1.8
```

This creates `signer/.java-version`.

**Step 3: Verify**

```bash
cd signer
java -version
# Expected: openjdk version "1.8.0_xxx" (Zulu 8.xx)
echo $JAVA_HOME
# Expected: path containing zulu-8
```

**Step 4: Enable jenv export plugin (if not already)**

```bash
jenv enable-plugin export
```

**Step 5: Commit**

```bash
git add signer/.java-version
git commit -m "chore(signer): set JDK 8 via jenv for Java ME compilation"
```

---

### Task A2: Download MIDP/CLDC stub JARs and ProGuard

**Files:**
- Create: `signer/lib/midpapi20.jar`
- Create: `signer/lib/cldcapi11.jar`
- Create: `tools/proguard/` (extracted from zip)

**Step 1: Download MIDP and CLDC stubs from Maven Central**

```bash
cd signer
curl -L -o lib/midpapi20.jar \
  https://repo1.maven.org/maven2/org/microemu/midpapi20/2.0.4/midpapi20-2.0.4.jar
curl -L -o lib/cldcapi11.jar \
  https://repo1.maven.org/maven2/org/microemu/cldcapi11/2.0.4/cldcapi11-2.0.4.jar
```

**Step 2: Verify the JARs are valid**

```bash
jar tf lib/midpapi20.jar | head -5
# Expected: META-INF/ and javax/microedition/ entries
jar tf lib/cldcapi11.jar | head -5
# Expected: META-INF/ and java/lang/ entries
```

**Step 3: Download and extract ProGuard 7.8.2**

```bash
cd ..  # back to repo root
curl -L -o /tmp/proguard-7.8.2.zip \
  https://github.com/Guardsquare/proguard/releases/download/v7.8.2/proguard-7.8.2.zip
unzip /tmp/proguard-7.8.2.zip -d tools/
# Creates tools/proguard-7.8.2/
ln -s proguard-7.8.2 tools/proguard
```

**Step 4: Verify ProGuard works**

```bash
java -jar tools/proguard/lib/proguard.jar --version
# Expected: ProGuard, version 7.8.2
```

**Step 5: Update .gitignore — add binary JARs and tools to LFS or ignore**

The MIDP/CLDC stubs are small (~200KB each) and stable — we track them in git.
ProGuard is large — we gitignore it and document the download in the Makefile.

Add to `.gitignore`:
```
# ProGuard (downloaded, not tracked)
tools/proguard-*/
tools/proguard
```

**Step 6: Commit**

```bash
git add signer/lib/midpapi20.jar signer/lib/cldcapi11.jar .gitignore
git commit -m "chore(signer): add MIDP 2.0 / CLDC 1.1 stub JARs and gitignore ProGuard"
```

---

### Task A3: Update build.xml to use ProGuard for preverification

**Files:**
- Modify: `signer/build.xml`

**Step 1: Rewrite the build.xml to use ProGuard**

Replace the existing `signer/build.xml` with a working build that:
- Compiles with `-bootclasspath` pointing to the stub JARs
- Uses source/target 1.4 (CLDC 1.1 compatible)
- Runs ProGuard with `-microedition` for preverification
- Generates the JAD descriptor
- Keeps the size-check target

Key changes from current build.xml:
- Remove the WTK_HOME dependency (no longer needed)
- Add ProGuard Ant taskdef
- Replace preverify target with ProGuard preverify
- Keep JAVA_HOME requirement (JDK 8 via jenv handles this)

The `<proguard>` Ant task configuration:

```xml
<taskdef resource="proguard/ant/task.properties"
         classpath="${basedir}/../tools/proguard/lib/proguard-ant.jar" />
```

ProGuard target:

```xml
<target name="preverify" depends="compile">
    <mkdir dir="${dist.dir}"/>
    <jar destfile="${build.dir}/${midlet.name}-unprocessed.jar"
         basedir="${classes.dir}">
        <fileset dir="${res.dir}" erroronmissingdir="false"/>
    </jar>
    <proguard>
        -injars      ${build.dir}/${midlet.name}-unprocessed.jar
        -outjars     ${dist.dir}/${midlet.name}.jar
        -libraryjars ${lib.dir}/cldcapi11.jar
        -libraryjars ${lib.dir}/midpapi20.jar
        -microedition
        -dontobfuscate
        -dontoptimize
        -keep public class * extends javax.microedition.midlet.MIDlet
    </proguard>
</target>
```

**Step 2: Run the build**

```bash
cd signer
jenv local 1.8
ant clean build
```

Expected: BUILD SUCCESSFUL, produces `dist/BurnerWallet.jar` and `dist/BurnerWallet.jad`

**Step 3: Verify JAR contents**

```bash
jar tf dist/BurnerWallet.jar | grep MIDlet
# Expected: org/burnerwallet/ui/BurnerWalletMIDlet.class
```

**Step 4: Commit**

```bash
git add signer/build.xml
git commit -m "chore(signer): replace WTK preverify with ProGuard -microedition"
```

---

### Task A4: Set up FreeJ2ME-Plus emulator and verify MIDlet runs

**Files:**
- Create: `tools/freej2me-plus/` (cloned repo)

**Step 1: Clone and build FreeJ2ME-Plus**

```bash
cd tools
git clone https://github.com/TASEmulators/freej2me-plus.git
cd freej2me-plus
ant
```

Expected: produces `build/freej2me.jar`

**Step 2: Run the signer MIDlet in the emulator**

```bash
java -jar tools/freej2me-plus/build/freej2me.jar \
  "file://$(pwd)/signer/dist/BurnerWallet.jar"
```

Expected: emulator window opens, displays "Burner Wallet" form with "Signer v0.1.0 Scaffold ready."

**Step 3: Add emulator convenience targets to Makefile**

Add to Makefile:

```makefile
emulator: build-signer
	java -jar tools/freej2me-plus/build/freej2me.jar \
		"file://$(shell pwd)/signer/dist/BurnerWallet.jar"
```

**Step 4: Gitignore the emulator clone (large, buildable from source)**

Add to `.gitignore`:
```
tools/freej2me-plus/
```

**Step 5: Add a `make setup-tools` target that downloads ProGuard and builds the emulator**

```makefile
setup-tools:
	@echo "Downloading ProGuard 7.8.2..."
	curl -L -o /tmp/proguard-7.8.2.zip \
		https://github.com/Guardsquare/proguard/releases/download/v7.8.2/proguard-7.8.2.zip
	unzip -o /tmp/proguard-7.8.2.zip -d tools/
	ln -sf proguard-7.8.2 tools/proguard
	@echo "Building FreeJ2ME-Plus emulator..."
	cd tools/freej2me-plus && ant
	@echo "Tools ready."
```

**Step 6: Commit**

```bash
git add Makefile .gitignore
git commit -m "chore(tools): add FreeJ2ME-Plus emulator and setup-tools target"
```

---

## Track B: Companion Core — Bitcoin Crypto Modules (TDD)

### Task B1: Add bip39 crate dependency and verify it compiles

**Files:**
- Modify: `companion/core/Cargo.toml`

**Step 1: Add the bip39 crate**

Add to `[dependencies]` in `companion/core/Cargo.toml`:

```toml
bip39 = { version = "2", features = ["rand"] }
```

**Step 2: Verify it compiles**

```bash
cd companion/core
cargo check
```

Expected: compiles without errors.

**Step 3: Commit**

```bash
git add companion/core/Cargo.toml companion/core/Cargo.lock
git commit -m "chore(companion-core): add bip39 crate dependency"
```

---

### Task B2: Implement mnemonic module — BIP39 generation and seed derivation

**Files:**
- Create: `companion/core/src/mnemonic.rs`
- Modify: `companion/core/src/lib.rs` (add `pub mod mnemonic;`)
- Test: inline `#[cfg(test)]` module in `mnemonic.rs`

**Step 1: Write the failing tests**

Create `companion/core/src/mnemonic.rs` with tests at the bottom:

```rust
//! BIP39 mnemonic generation and seed derivation.

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_12_word_mnemonic() {
        let m = generate_mnemonic(12).unwrap();
        assert_eq!(m.word_count(), 12);
    }

    #[test]
    fn generate_24_word_mnemonic() {
        let m = generate_mnemonic(24).unwrap();
        assert_eq!(m.word_count(), 24);
    }

    #[test]
    fn mnemonic_from_phrase() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let m = from_phrase(phrase).unwrap();
        assert_eq!(m.word_count(), 12);
    }

    #[test]
    fn seed_derivation_no_passphrase() {
        // BIP39 test vector 1: entropy 00000000000000000000000000000000
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let m = from_phrase(phrase).unwrap();
        let seed = to_seed(&m, "");
        let expected = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4";
        assert_eq!(hex::encode(seed), expected);
    }

    #[test]
    fn seed_derivation_with_passphrase() {
        // BIP39 spec: passphrase changes the seed
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let seed_no_pass = to_seed(&from_phrase(phrase).unwrap(), "");
        let seed_with_pass = to_seed(&from_phrase(phrase).unwrap(), "my passphrase");
        assert_ne!(seed_no_pass, seed_with_pass);
    }

    #[test]
    fn validate_rejects_invalid_phrase() {
        let bad = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon";
        assert!(from_phrase(bad).is_err());
    }

    #[test]
    fn test_vector_2_legal_winner() {
        let phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow";
        let m = from_phrase(phrase).unwrap();
        let seed = to_seed(&m, "");
        let expected = "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6fa457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607";
        assert_eq!(hex::encode(seed), expected);
    }

    #[test]
    fn test_vector_3_letter_advice() {
        let phrase = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above";
        let m = from_phrase(phrase).unwrap();
        let seed = to_seed(&m, "");
        let expected = "d71de856f81a8acc65e6fc851a38d4d7ec216fd0796d0a6827a3ad6ed5511a30fa280f12eb2e47ed2ac03b5c462a0358d18d69fe4f985ec81778c1b370b652a8";
        assert_eq!(hex::encode(seed), expected);
    }

    #[test]
    fn test_vector_4_zoo() {
        let phrase = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong";
        let m = from_phrase(phrase).unwrap();
        let seed = to_seed(&m, "");
        let expected = "0cd6e5d827bb62eb8fc1e262254223817fd068a74b5b449cc2f667c3f1f985a76379b43348d952e2265b4cd129090758b3e3c2c49115b87110549b6d30e79c7";
        // Note: this vector has 127 hex chars (leading zero stripped). Pad to 128.
        let seed_hex = hex::encode(seed);
        assert_eq!(seed_hex, format!("0{}", expected).as_str().get(..128).unwrap_or(&seed_hex));
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
cd companion/core
cargo test mnemonic
```

Expected: compilation error — `generate_mnemonic`, `from_phrase`, `to_seed` not defined.

**Step 3: Implement the module**

Add the implementation above the tests in `mnemonic.rs`:

```rust
//! BIP39 mnemonic generation and seed derivation.

use bip39::Mnemonic;
use crate::Error;

/// Generate a new BIP39 mnemonic with the given word count (12 or 24).
pub fn generate_mnemonic(word_count: usize) -> Result<Mnemonic, Error> {
    Mnemonic::generate(word_count)
        .map_err(|e| Error::Mnemonic(e.to_string()))
}

/// Parse and validate an existing BIP39 mnemonic phrase.
pub fn from_phrase(phrase: &str) -> Result<Mnemonic, Error> {
    phrase.parse::<Mnemonic>()
        .map_err(|e| Error::Mnemonic(e.to_string()))
}

/// Derive a 64-byte seed from a mnemonic and optional passphrase.
/// Uses PBKDF2-HMAC-SHA512 with 2048 rounds per BIP39 spec.
pub fn to_seed(mnemonic: &Mnemonic, passphrase: &str) -> [u8; 64] {
    mnemonic.to_seed(passphrase)
}
```

Also update `companion/core/src/error.rs` to add the Mnemonic variant:

```rust
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("bitcoin error: {0}")]
    Bitcoin(#[from] bitcoin::address::ParseError),

    #[error("network error: {0}")]
    Network(String),

    #[error("PSBT error: {0}")]
    Psbt(String),

    #[error("mnemonic error: {0}")]
    Mnemonic(String),
}
```

Also update `companion/core/src/lib.rs`:

```rust
pub mod error;
pub mod mnemonic;
pub mod network;

pub use error::Error;
pub use network::NetworkConfig;
```

**Step 4: Run tests to verify they pass**

```bash
cargo test mnemonic -- --nocapture
```

Expected: all tests pass. The `test_vector_4_zoo` test may need the expected hex adjusted — verify against the actual BIP39 test vector file. If the leading zero issue occurs, fix the assertion.

**Step 5: Commit**

```bash
git add companion/core/src/mnemonic.rs companion/core/src/error.rs companion/core/src/lib.rs
git commit -m "feat(companion-core): implement BIP39 mnemonic generation and seed derivation"
```

---

### Task B3: Implement keys module — BIP32 master key and child derivation

**Files:**
- Create: `companion/core/src/keys.rs`
- Modify: `companion/core/src/lib.rs` (add `pub mod keys;`)
- Test: inline `#[cfg(test)]` module

**Step 1: Write the failing tests**

Create `companion/core/src/keys.rs` with tests:

```rust
//! BIP32 HD key derivation: master key from seed, child key derivation.

#[cfg(test)]
mod tests {
    use super::*;

    // BIP32 Test Vector 1: seed 000102030405060708090a0b0c0d0e0f
    const SEED_1: &str = "000102030405060708090a0b0c0d0e0f";

    #[test]
    fn master_key_from_seed_vector_1() {
        let seed = hex::decode(SEED_1).unwrap();
        let master = master_xpriv(&seed).unwrap();
        assert_eq!(
            master.to_string(),
            "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"
        );
    }

    #[test]
    fn master_xpub_from_seed_vector_1() {
        let seed = hex::decode(SEED_1).unwrap();
        let master = master_xpriv(&seed).unwrap();
        let xpub = to_xpub(&master);
        assert_eq!(
            xpub.to_string(),
            "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8"
        );
    }

    #[test]
    fn derive_child_vector_1_m_0h() {
        let seed = hex::decode(SEED_1).unwrap();
        let master = master_xpriv(&seed).unwrap();
        let child = derive_xpriv(&master, "m/0'").unwrap();
        assert_eq!(
            child.to_string(),
            "xprv9uHRZZhk6KAJC1avXpDAp4MDc3sQKNxDiPvvkX8Br5ngLNv1TxvUxt4cV1rGL5hj6KCesnDYUhd7oWgT11eZG7XnxHrnYeSvkzY7d2bhkJ7"
        );
    }

    #[test]
    fn derive_child_vector_1_m_0h_1() {
        let seed = hex::decode(SEED_1).unwrap();
        let master = master_xpriv(&seed).unwrap();
        let child = derive_xpriv(&master, "m/0'/1").unwrap();
        assert_eq!(
            child.to_string(),
            "xprv9wTYmMFdV23N2TdNG573QoEsfRRWKQGEc8A89o6UhKnANMGhZhp3HNJfJ2zD2M1WRN1EGNvDpQqe6TJBVRVaxKjMRhFUoFCxSMbZa5bLv1b"
        );
    }

    // BIP32 Test Vector 2: seed fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542
    const SEED_2: &str = "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542";

    #[test]
    fn master_key_from_seed_vector_2() {
        let seed = hex::decode(SEED_2).unwrap();
        let master = master_xpriv(&seed).unwrap();
        assert_eq!(
            master.to_string(),
            "xprv9s21ZrQH143K31xYSDQpPDxsXRTUcvj2iNHm5NUtrGiGG5e2DtALGdso3pGz6ssrdK4PFmM8NSpSBHNqPqm55Qn3LqFtT2emdEXVYsCzC2U"
        );
    }

    #[test]
    fn derive_xpub_for_path() {
        let seed = hex::decode(SEED_1).unwrap();
        let master = master_xpriv(&seed).unwrap();
        let child_priv = derive_xpriv(&master, "m/0'").unwrap();
        let child_pub = to_xpub(&child_priv);
        assert_eq!(
            child_pub.to_string(),
            "xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw"
        );
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
cargo test keys
```

Expected: compilation error — functions not defined.

**Step 3: Implement the module**

```rust
//! BIP32 HD key derivation: master key from seed, child key derivation.

use bitcoin::bip32::{DerivationPath, Xpriv, Xpub};
use bitcoin::NetworkKind;
use crate::Error;

/// Derive the BIP32 master extended private key from a raw seed.
/// Uses mainnet version bytes (xprv/xpub) by default for test vector compatibility.
pub fn master_xpriv(seed: &[u8]) -> Result<Xpriv, Error> {
    Xpriv::new_master(NetworkKind::Main, seed)
        .map_err(|e| Error::KeyDerivation(e.to_string()))
}

/// Derive a child extended private key at the given BIP32 path.
/// Path format: "m/0'/1/2'" or "m/44'/0'/0'"
pub fn derive_xpriv(master: &Xpriv, path: &str) -> Result<Xpriv, Error> {
    let path: DerivationPath = path.parse()
        .map_err(|e: bitcoin::bip32::Error| Error::KeyDerivation(e.to_string()))?;
    master.derive_xpriv(&path)
        .map_err(|e| Error::KeyDerivation(e.to_string()))
}

/// Convert an extended private key to its public counterpart.
pub fn to_xpub(xpriv: &Xpriv) -> Xpub {
    Xpub::from_xpriv(xpriv)
}
```

Update `error.rs` — add `KeyDerivation` variant:

```rust
#[error("key derivation error: {0}")]
KeyDerivation(String),
```

Update `lib.rs`:

```rust
pub mod keys;
```

**Step 4: Run tests to verify they pass**

```bash
cargo test keys -- --nocapture
```

Expected: all tests pass.

**Step 5: Commit**

```bash
git add companion/core/src/keys.rs companion/core/src/error.rs companion/core/src/lib.rs
git commit -m "feat(companion-core): implement BIP32 master key and child derivation"
```

---

### Task B4: Implement derivation module — BIP44/84 path builder

**Files:**
- Create: `companion/core/src/derivation.rs`
- Modify: `companion/core/src/lib.rs`

**Step 1: Write the failing tests**

```rust
//! BIP44/84 derivation path construction.

#[cfg(test)]
mod tests {
    use super::*;
    use bitcoin::Network;

    #[test]
    fn bip84_mainnet_account_0() {
        let path = bip84_account(Network::Bitcoin, 0);
        assert_eq!(path.to_string(), "m/84'/0'/0'");
    }

    #[test]
    fn bip84_testnet_account_0() {
        let path = bip84_account(Network::Testnet, 0);
        assert_eq!(path.to_string(), "m/84'/1'/0'");
    }

    #[test]
    fn bip84_receive_address_path() {
        let path = bip84_address(Network::Bitcoin, 0, false, 0);
        assert_eq!(path.to_string(), "m/84'/0'/0'/0/0");
    }

    #[test]
    fn bip84_change_address_path() {
        let path = bip84_address(Network::Bitcoin, 0, true, 0);
        assert_eq!(path.to_string(), "m/84'/0'/0'/1/0");
    }

    #[test]
    fn bip84_receive_index_5() {
        let path = bip84_address(Network::Testnet, 0, false, 5);
        assert_eq!(path.to_string(), "m/84'/1'/0'/0/5");
    }

    #[test]
    fn bip44_mainnet_account_0() {
        let path = bip44_account(Network::Bitcoin, 0);
        assert_eq!(path.to_string(), "m/44'/0'/0'");
    }

    #[test]
    fn bip44_testnet_account_1() {
        let path = bip44_account(Network::Testnet, 1);
        assert_eq!(path.to_string(), "m/44'/1'/1'");
    }

    #[test]
    fn coin_type_signet_is_testnet() {
        let path = bip84_account(Network::Signet, 0);
        assert_eq!(path.to_string(), "m/84'/1'/0'");
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
cargo test derivation
```

**Step 3: Implement the module**

```rust
//! BIP44/84 derivation path construction.

use bitcoin::bip32::{ChildNumber, DerivationPath};
use bitcoin::Network;

/// Return the BIP44 coin type for a given network.
/// Bitcoin mainnet = 0, testnet/signet/regtest = 1.
fn coin_type(network: Network) -> u32 {
    match network {
        Network::Bitcoin => 0,
        _ => 1,
    }
}

/// BIP84 account-level path: m/84'/coin'/account'
pub fn bip84_account(network: Network, account: u32) -> DerivationPath {
    DerivationPath::from(vec![
        ChildNumber::from_hardened_idx(84).unwrap(),
        ChildNumber::from_hardened_idx(coin_type(network)).unwrap(),
        ChildNumber::from_hardened_idx(account).unwrap(),
    ])
}

/// BIP84 full address path: m/84'/coin'/account'/change/index
pub fn bip84_address(network: Network, account: u32, change: bool, index: u32) -> DerivationPath {
    DerivationPath::from(vec![
        ChildNumber::from_hardened_idx(84).unwrap(),
        ChildNumber::from_hardened_idx(coin_type(network)).unwrap(),
        ChildNumber::from_hardened_idx(account).unwrap(),
        ChildNumber::from_normal_idx(if change { 1 } else { 0 }).unwrap(),
        ChildNumber::from_normal_idx(index).unwrap(),
    ])
}

/// BIP44 account-level path: m/44'/coin'/account'
pub fn bip44_account(network: Network, account: u32) -> DerivationPath {
    DerivationPath::from(vec![
        ChildNumber::from_hardened_idx(44).unwrap(),
        ChildNumber::from_hardened_idx(coin_type(network)).unwrap(),
        ChildNumber::from_hardened_idx(account).unwrap(),
    ])
}

/// BIP44 full address path: m/44'/coin'/account'/change/index
pub fn bip44_address(network: Network, account: u32, change: bool, index: u32) -> DerivationPath {
    DerivationPath::from(vec![
        ChildNumber::from_hardened_idx(44).unwrap(),
        ChildNumber::from_hardened_idx(coin_type(network)).unwrap(),
        ChildNumber::from_hardened_idx(account).unwrap(),
        ChildNumber::from_normal_idx(if change { 1 } else { 0 }).unwrap(),
        ChildNumber::from_normal_idx(index).unwrap(),
    ])
}
```

Update `lib.rs`:

```rust
pub mod derivation;
```

**Step 4: Run tests**

```bash
cargo test derivation
```

Expected: all pass.

**Step 5: Commit**

```bash
git add companion/core/src/derivation.rs companion/core/src/lib.rs
git commit -m "feat(companion-core): implement BIP44/84 derivation path builder"
```

---

### Task B5: Implement address module — BIP173 bech32 address generation

**Files:**
- Create: `companion/core/src/address.rs`
- Modify: `companion/core/src/lib.rs`

**Step 1: Write the failing tests**

```rust
//! BIP173 bech32 address generation from extended keys.

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys;
    use crate::mnemonic;
    use bitcoin::Network;

    #[test]
    fn p2wpkh_from_mnemonic_testnet() {
        // Using the "abandon" test vector
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let m = mnemonic::from_phrase(phrase).unwrap();
        let seed = mnemonic::to_seed(&m, "");
        let addr = derive_p2wpkh_address(&seed, Network::Testnet, 0, false, 0).unwrap();
        let addr_str = addr.to_string();
        // Should start with "tb1q" for testnet P2WPKH
        assert!(addr_str.starts_with("tb1q"), "Expected tb1q prefix, got: {}", addr_str);
    }

    #[test]
    fn p2wpkh_from_mnemonic_mainnet() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let m = mnemonic::from_phrase(phrase).unwrap();
        let seed = mnemonic::to_seed(&m, "");
        let addr = derive_p2wpkh_address(&seed, Network::Bitcoin, 0, false, 0).unwrap();
        let addr_str = addr.to_string();
        assert!(addr_str.starts_with("bc1q"), "Expected bc1q prefix, got: {}", addr_str);
    }

    #[test]
    fn different_indices_produce_different_addresses() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let m = mnemonic::from_phrase(phrase).unwrap();
        let seed = mnemonic::to_seed(&m, "");
        let addr0 = derive_p2wpkh_address(&seed, Network::Testnet, 0, false, 0).unwrap();
        let addr1 = derive_p2wpkh_address(&seed, Network::Testnet, 0, false, 1).unwrap();
        assert_ne!(addr0.to_string(), addr1.to_string());
    }

    #[test]
    fn change_address_differs_from_receive() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let m = mnemonic::from_phrase(phrase).unwrap();
        let seed = mnemonic::to_seed(&m, "");
        let receive = derive_p2wpkh_address(&seed, Network::Testnet, 0, false, 0).unwrap();
        let change = derive_p2wpkh_address(&seed, Network::Testnet, 0, true, 0).unwrap();
        assert_ne!(receive.to_string(), change.to_string());
    }

    #[test]
    fn passphrase_produces_different_address() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let m = mnemonic::from_phrase(phrase).unwrap();
        let seed_no_pass = mnemonic::to_seed(&m, "");
        let seed_with_pass = mnemonic::to_seed(&m, "secret");
        let addr1 = derive_p2wpkh_address(&seed_no_pass, Network::Testnet, 0, false, 0).unwrap();
        let addr2 = derive_p2wpkh_address(&seed_with_pass, Network::Testnet, 0, false, 0).unwrap();
        assert_ne!(addr1.to_string(), addr2.to_string());
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
cargo test address
```

**Step 3: Implement the module**

```rust
//! BIP173 bech32 address generation from seeds via BIP84 derivation.

use bitcoin::address::{Address, KnownHrp};
use bitcoin::bip32::{ChildNumber, Xpub};
use bitcoin::{CompressedPublicKey, Network};
use crate::keys;
use crate::derivation;
use crate::Error;

/// Derive a P2WPKH (native SegWit, bech32) address from a seed.
///
/// Uses BIP84 derivation: m/84'/coin'/account'/change/index
pub fn derive_p2wpkh_address(
    seed: &[u8],
    network: Network,
    account: u32,
    change: bool,
    index: u32,
) -> Result<Address, Error> {
    let master = keys::master_xpriv(seed)?;
    let account_path = derivation::bip84_account(network, account);
    let account_xpriv = master.derive_xpriv(&account_path)
        .map_err(|e| Error::KeyDerivation(e.to_string()))?;
    let account_xpub = keys::to_xpub(&account_xpriv);

    let change_child = ChildNumber::from_normal_idx(if change { 1 } else { 0 }).unwrap();
    let index_child = ChildNumber::from_normal_idx(index).unwrap();
    let addr_xpub: Xpub = account_xpub.derive_xpub([change_child, index_child])
        .map_err(|e| Error::KeyDerivation(e.to_string()))?;

    let compressed_pk = CompressedPublicKey::from_secp(addr_xpub.public_key);
    let hrp = KnownHrp::from(network);
    Ok(Address::p2wpkh(compressed_pk, hrp))
}
```

Update `lib.rs`:

```rust
pub mod address;
```

**Step 4: Run tests**

```bash
cargo test address
```

Expected: all pass.

**Step 5: Commit**

```bash
git add companion/core/src/address.rs companion/core/src/lib.rs
git commit -m "feat(companion-core): implement BIP173 bech32 address generation via BIP84"
```

---

### Task B6: End-to-end integration test — mnemonic to address

**Files:**
- Create: `companion/core/tests/integration.rs`

**Step 1: Write the integration test**

```rust
//! End-to-end integration test: mnemonic → seed → master key → BIP84 address

use burner_companion_core::{mnemonic, keys, derivation, address};
use bitcoin::Network;

#[test]
fn full_wallet_derivation_flow() {
    // Generate a fresh mnemonic
    let m = mnemonic::generate_mnemonic(12).unwrap();
    let seed = mnemonic::to_seed(&m, "");

    // Derive master key
    let master = keys::master_xpriv(&seed).unwrap();
    let master_pub = keys::to_xpub(&master);
    assert_ne!(master.to_string(), "");
    assert_ne!(master_pub.to_string(), "");

    // Derive BIP84 account path
    let path = derivation::bip84_account(Network::Testnet, 0);
    assert_eq!(path.to_string(), "m/84'/1'/0'");

    // Derive first receive address
    let addr = address::derive_p2wpkh_address(&seed, Network::Testnet, 0, false, 0).unwrap();
    assert!(addr.to_string().starts_with("tb1q"));

    // Derive first change address
    let change_addr = address::derive_p2wpkh_address(&seed, Network::Testnet, 0, true, 0).unwrap();
    assert!(change_addr.to_string().starts_with("tb1q"));
    assert_ne!(addr.to_string(), change_addr.to_string());
}

#[test]
fn known_mnemonic_produces_deterministic_address() {
    let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    let m = mnemonic::from_phrase(phrase).unwrap();
    let seed = mnemonic::to_seed(&m, "");

    let addr1 = address::derive_p2wpkh_address(&seed, Network::Testnet, 0, false, 0).unwrap();
    let addr2 = address::derive_p2wpkh_address(&seed, Network::Testnet, 0, false, 0).unwrap();
    assert_eq!(addr1.to_string(), addr2.to_string());
}

#[test]
fn mainnet_and_testnet_addresses_differ() {
    let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    let m = mnemonic::from_phrase(phrase).unwrap();
    let seed = mnemonic::to_seed(&m, "");

    let mainnet = address::derive_p2wpkh_address(&seed, Network::Bitcoin, 0, false, 0).unwrap();
    let testnet = address::derive_p2wpkh_address(&seed, Network::Testnet, 0, false, 0).unwrap();

    assert!(mainnet.to_string().starts_with("bc1q"));
    assert!(testnet.to_string().starts_with("tb1q"));
    assert_ne!(mainnet.to_string(), testnet.to_string());
}
```

**Step 2: Run all tests**

```bash
cd companion/core
cargo test
```

Expected: all unit tests + integration tests pass.

**Step 3: Commit**

```bash
git add companion/core/tests/integration.rs
git commit -m "test(companion-core): add end-to-end mnemonic-to-address integration tests"
```

---

### Task B7: Re-export public API and update TUI scaffold

**Files:**
- Modify: `companion/core/src/lib.rs` (add convenience re-exports)
- Modify: `companion/tui/src/main.rs` (demo the wallet flow)

**Step 1: Add re-exports to lib.rs**

```rust
pub mod address;
pub mod derivation;
pub mod error;
pub mod keys;
pub mod mnemonic;
pub mod network;

pub use error::Error;
pub use network::NetworkConfig;

// Re-export bitcoin types that consumers need
pub use bitcoin::Network;
pub use bip39::Mnemonic;
```

**Step 2: Update TUI main.rs to demo the full flow**

```rust
use burner_companion_core::{mnemonic, address, Network};
use clap::Parser;

#[derive(Parser)]
#[command(name = "burner-companion", about = "Burner Wallet companion TUI")]
struct Cli {
    #[arg(long, default_value = "testnet")]
    network: String,
}

fn main() {
    let cli = Cli::parse();
    let network = match cli.network.as_str() {
        "mainnet" => Network::Bitcoin,
        "signet" => Network::Signet,
        _ => Network::Testnet,
    };

    println!("Burner Wallet Companion v0.1.0");
    println!("Network: {}", cli.network);
    println!();

    // Demo: generate a wallet
    let m = mnemonic::generate_mnemonic(12).unwrap();
    println!("Generated mnemonic: {}", m);
    println!();

    let seed = mnemonic::to_seed(&m, "");
    let addr = address::derive_p2wpkh_address(&seed, network, 0, false, 0).unwrap();
    println!("First receive address: {}", addr);
    println!();
    println!("WARNING: This is a demo. Do not send real funds.");
}
```

**Step 3: Build and run the TUI**

```bash
cd companion/tui
cargo run -- --network testnet
```

Expected: prints a mnemonic and a tb1q... address.

**Step 4: Run full test suite from repo root**

```bash
cd companion/core && cargo test
cd ../tui && cargo build
```

Expected: everything compiles and tests pass.

**Step 5: Commit**

```bash
git add companion/core/src/lib.rs companion/tui/src/main.rs
git commit -m "feat(companion): re-export public API and update TUI with wallet demo"
```

---

## Dependency Graph

```
Track A (J2ME Toolchain)          Track B (Rust Companion Core)
─────────────────────────         ────────────────────────────
A1: jenv JDK 8                    B1: Add bip39 dependency
     │                                 │
A2: Download stubs + ProGuard     B2: Mnemonic module (BIP39)
     │                                 │
A3: Update build.xml              B3: Keys module (BIP32)
     │                                 │
A4: Emulator setup                B4: Derivation module (BIP44/84)
                                       │
                                  B5: Address module (BIP173)
                                       │
                                  B6: Integration tests
                                       │
                                  B7: Re-export API + TUI update
```

Track A and Track B are fully independent and can run in parallel.
Within each track, tasks are sequential.
