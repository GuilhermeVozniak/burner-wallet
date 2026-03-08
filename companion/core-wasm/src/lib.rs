//! WASM bridge for Burner Wallet companion crypto functions.
//!
//! Exposes the offline/crypto subset of the companion core for use in
//! web and browser extension frontends. Networking (wallet sync,
//! broadcasting) stays in JavaScript — this crate handles:
//! - BIP39 mnemonic generation and validation
//! - Seed derivation (PBKDF2-HMAC-SHA512)
//! - BIP84 P2WPKH address derivation
//! - PSBT format conversion (base64 <-> hex)

use wasm_bindgen::prelude::*;

use bip39::Mnemonic;
use bitcoin::bip32::{ChildNumber, DerivationPath, Xpriv, Xpub};
use bitcoin::address::{Address, KnownHrp};
use bitcoin::psbt::Psbt;
use bitcoin::secp256k1::Secp256k1;
use bitcoin::{Network, NetworkKind};

// ---------------------------------------------------------------------------
// BIP39 Mnemonic
// ---------------------------------------------------------------------------

/// Generate a new BIP39 mnemonic with the given word count (12 or 24).
#[wasm_bindgen]
pub fn generate_mnemonic(word_count: u32) -> Result<String, JsValue> {
    let m = Mnemonic::generate(word_count as usize)
        .map_err(|e| JsValue::from_str(&format!("Mnemonic generation failed: {}", e)))?;
    Ok(m.to_string())
}

/// Check whether a BIP39 mnemonic phrase is valid.
#[wasm_bindgen]
pub fn validate_mnemonic(phrase: &str) -> bool {
    phrase.parse::<Mnemonic>().is_ok()
}

/// Derive a 64-byte BIP39 seed from a mnemonic phrase and passphrase.
///
/// Returns the raw seed bytes (64 bytes). Uses PBKDF2-HMAC-SHA512 with
/// 2048 rounds per the BIP39 specification.
#[wasm_bindgen]
pub fn mnemonic_to_seed(phrase: &str, passphrase: &str) -> Result<Vec<u8>, JsValue> {
    let m: Mnemonic = phrase
        .parse()
        .map_err(|e| JsValue::from_str(&format!("Invalid mnemonic: {}", e)))?;
    Ok(m.to_seed(passphrase).to_vec())
}

// ---------------------------------------------------------------------------
// BIP84 Address Derivation
// ---------------------------------------------------------------------------

/// Parse a network string ("mainnet", "testnet", "signet", "regtest") into
/// a `bitcoin::Network`.
fn parse_network(network: &str) -> Result<Network, JsValue> {
    match network {
        "mainnet" | "bitcoin" => Ok(Network::Bitcoin),
        "testnet" | "testnet3" => Ok(Network::Testnet),
        "signet" => Ok(Network::Signet),
        "regtest" => Ok(Network::Regtest),
        _ => Err(JsValue::from_str(&format!(
            "Unknown network '{}'. Use: mainnet, testnet, signet, regtest",
            network
        ))),
    }
}

/// Return the BIP44 coin type for a given network (0 for mainnet, 1 otherwise).
fn coin_type(network: Network) -> u32 {
    match network {
        Network::Bitcoin => 0,
        _ => 1,
    }
}

/// Derive a BIP84 P2WPKH (native SegWit bech32) address from a raw seed.
///
/// Derivation path: m/84'/coin'/account'/0/index
///
/// - `seed`: 64-byte BIP39 seed (from `mnemonic_to_seed`)
/// - `network`: "mainnet", "testnet", "signet", or "regtest"
/// - `account`: BIP44 account index (usually 0)
/// - `index`: Address index within the receive chain
#[wasm_bindgen]
pub fn derive_address(
    seed: &[u8],
    network: &str,
    account: u32,
    index: u32,
) -> Result<String, JsValue> {
    let net = parse_network(network)?;
    let network_kind = match net {
        Network::Bitcoin => NetworkKind::Main,
        _ => NetworkKind::Test,
    };

    let secp = Secp256k1::new();

    // Master key from seed
    let master = Xpriv::new_master(network_kind, seed)
        .map_err(|e| JsValue::from_str(&format!("Master key derivation failed: {}", e)))?;

    // BIP84 account path: m/84'/coin'/account'
    let account_path = DerivationPath::from(vec![
        ChildNumber::from_hardened_idx(84).unwrap(),
        ChildNumber::from_hardened_idx(coin_type(net)).unwrap(),
        ChildNumber::from_hardened_idx(account).unwrap(),
    ]);

    let account_xpriv = master
        .derive_priv(&secp, &account_path)
        .map_err(|e| JsValue::from_str(&format!("Account derivation failed: {}", e)))?;
    let account_xpub = Xpub::from_priv(&secp, &account_xpriv);

    // Derive receive chain (change=0) then index
    let change_child = ChildNumber::from_normal_idx(0).unwrap();
    let index_child = ChildNumber::from_normal_idx(index).unwrap();
    let addr_xpub = account_xpub
        .derive_pub(&secp, &[change_child, index_child])
        .map_err(|e| JsValue::from_str(&format!("Address derivation failed: {}", e)))?;

    let compressed_pk = addr_xpub.to_pub();
    let hrp = KnownHrp::from(net);
    let address = Address::p2wpkh(&compressed_pk, hrp);

    Ok(address.to_string())
}

// ---------------------------------------------------------------------------
// PSBT Format Conversion
// ---------------------------------------------------------------------------

/// Convert a base64-encoded PSBT to its hex representation.
///
/// This is useful for QR transport where hex encoding may be preferred.
#[wasm_bindgen]
pub fn serialize_psbt_hex(psbt_base64: &str) -> Result<String, JsValue> {
    use bitcoin::base64::{engine::general_purpose::STANDARD, Engine};
    let raw = STANDARD
        .decode(psbt_base64)
        .map_err(|e| JsValue::from_str(&format!("Invalid base64: {}", e)))?;
    let psbt = Psbt::deserialize(&raw)
        .map_err(|e| JsValue::from_str(&format!("Invalid PSBT: {}", e)))?;
    let bytes = psbt.serialize();
    Ok(hex::encode(bytes))
}

/// Convert a hex-encoded PSBT to its base64 representation.
///
/// This is useful for interoperability with wallets that expect base64 PSBTs.
#[wasm_bindgen]
pub fn deserialize_psbt_base64(hex_str: &str) -> Result<String, JsValue> {
    let bytes = hex::decode(hex_str)
        .map_err(|e| JsValue::from_str(&format!("Invalid hex: {}", e)))?;
    let psbt = Psbt::deserialize(&bytes)
        .map_err(|e| JsValue::from_str(&format!("Invalid PSBT bytes: {}", e)))?;
    // Re-serialize to binary and base64 encode
    use bitcoin::base64::{engine::general_purpose::STANDARD, Engine};
    let psbt_bytes = psbt.serialize();
    Ok(STANDARD.encode(psbt_bytes))
}

// ---------------------------------------------------------------------------
// Native tests (run with `cargo test`)
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_mnemonic_12() {
        let phrase = generate_mnemonic(12).unwrap();
        let words: Vec<&str> = phrase.split_whitespace().collect();
        assert_eq!(words.len(), 12);
        assert!(validate_mnemonic(&phrase));
    }

    #[test]
    fn test_generate_mnemonic_24() {
        let phrase = generate_mnemonic(24).unwrap();
        let words: Vec<&str> = phrase.split_whitespace().collect();
        assert_eq!(words.len(), 24);
        assert!(validate_mnemonic(&phrase));
    }

    #[test]
    fn test_validate_mnemonic_valid() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        assert!(validate_mnemonic(phrase));
    }

    #[test]
    fn test_validate_mnemonic_invalid() {
        assert!(!validate_mnemonic("not a valid mnemonic phrase at all"));
        assert!(!validate_mnemonic(""));
        // Wrong checksum
        assert!(!validate_mnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"));
    }

    #[test]
    fn test_mnemonic_to_seed_known_vector() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let seed = mnemonic_to_seed(phrase, "").unwrap();
        assert_eq!(seed.len(), 64);
        let expected = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4";
        assert_eq!(hex::encode(&seed), expected);
    }

    #[test]
    fn test_mnemonic_to_seed_with_passphrase() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let seed_no_pass = mnemonic_to_seed(phrase, "").unwrap();
        let seed_with_pass = mnemonic_to_seed(phrase, "my secret").unwrap();
        assert_ne!(seed_no_pass, seed_with_pass);
    }

    #[test]
    fn test_mnemonic_to_seed_trezor_vector() {
        let phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow";
        let seed = mnemonic_to_seed(phrase, "TREZOR").unwrap();
        let expected = "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6fa457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607";
        assert_eq!(hex::encode(&seed), expected);
    }

    // Error-path tests (mnemonic_to_seed with invalid input, etc.) are skipped
    // in native mode because wasm_bindgen's JsValue::from_str panics without
    // a JS runtime. These are covered by wasm-bindgen-test in WASM mode.

    #[test]
    fn test_derive_address_mainnet_cross_impl() {
        // Cross-implementation vector: m/84'/0'/0'/0/0
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let seed = mnemonic_to_seed(phrase, "").unwrap();
        let addr = derive_address(&seed, "mainnet", 0, 0).unwrap();
        assert_eq!(addr, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu");
    }

    #[test]
    fn test_derive_address_mainnet_index_1() {
        // Cross-implementation vector: m/84'/0'/0'/0/1
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let seed = mnemonic_to_seed(phrase, "").unwrap();
        let addr = derive_address(&seed, "mainnet", 0, 1).unwrap();
        assert_eq!(addr, "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g");
    }

    #[test]
    fn test_derive_address_testnet_cross_impl() {
        // Cross-implementation vector: m/84'/1'/0'/0/0
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let seed = mnemonic_to_seed(phrase, "").unwrap();
        let addr = derive_address(&seed, "testnet", 0, 0).unwrap();
        assert_eq!(addr, "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl");
    }

    #[test]
    fn test_derive_address_testnet_index_1() {
        // Cross-implementation vector: m/84'/1'/0'/0/1
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let seed = mnemonic_to_seed(phrase, "").unwrap();
        let addr = derive_address(&seed, "testnet", 0, 1).unwrap();
        assert_eq!(addr, "tb1qd7spv5q28348xl4myc8zmh983w5jx32cjhkn97");
    }

    #[test]
    fn test_derive_address_different_indices() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let seed = mnemonic_to_seed(phrase, "").unwrap();
        let addr0 = derive_address(&seed, "testnet", 0, 0).unwrap();
        let addr1 = derive_address(&seed, "testnet", 0, 1).unwrap();
        assert_ne!(addr0, addr1);
    }

    // derive_address with invalid network test is skipped in native mode
    // because JsValue::from_str panics without a JS runtime.

    #[test]
    fn test_psbt_hex_roundtrip() {
        // Create a minimal PSBT, convert to base64, then roundtrip through hex
        use bitcoin::blockdata::locktime::absolute;
        use bitcoin::blockdata::transaction::{self, OutPoint, Sequence, TxIn};
        use bitcoin::blockdata::witness::Witness;
        use bitcoin::hashes::Hash;
        use bitcoin::{Amount, ScriptBuf, Transaction, Txid};

        let unsigned_tx = Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint {
                    txid: Txid::from_byte_array([0x01; 32]),
                    vout: 0,
                },
                script_sig: ScriptBuf::new(),
                sequence: Sequence::MAX,
                witness: Witness::default(),
            }],
            output: vec![bitcoin::TxOut {
                value: Amount::from_sat(50_000),
                script_pubkey: ScriptBuf::new(),
            }],
        };

        let psbt = Psbt {
            unsigned_tx,
            version: 0,
            xpub: Default::default(),
            proprietary: Default::default(),
            unknown: Default::default(),
            inputs: vec![bitcoin::psbt::Input::default()],
            outputs: vec![bitcoin::psbt::Output::default()],
        };

        use bitcoin::base64::{engine::general_purpose::STANDARD, Engine};
        let base64_str = STANDARD.encode(psbt.serialize());

        // base64 -> hex
        let hex_str = serialize_psbt_hex(&base64_str).unwrap();
        // Verify hex starts with psbt magic
        assert!(
            hex_str.starts_with("70736274ff"),
            "Expected PSBT magic in hex"
        );

        // hex -> base64
        let roundtripped_base64 = deserialize_psbt_base64(&hex_str).unwrap();

        // Parse both and verify they produce the same transaction
        let original_bytes = STANDARD.decode(&base64_str).unwrap();
        let roundtripped_bytes = STANDARD.decode(&roundtripped_base64).unwrap();
        let original = Psbt::deserialize(&original_bytes).unwrap();
        let roundtripped = Psbt::deserialize(&roundtripped_bytes).unwrap();
        assert_eq!(
            original.unsigned_tx.compute_txid(),
            roundtripped.unsigned_tx.compute_txid()
        );
    }

    // Error-path PSBT tests are skipped in native mode because
    // JsValue::from_str panics without a JS runtime.
}
