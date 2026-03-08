use napi_derive::napi;

use burner_companion_core::address;
use burner_companion_core::mnemonic;
use burner_companion_core::wallet;

/// Parse a network string ("bitcoin", "testnet", "signet", "regtest") into bitcoin::Network.
fn parse_network(network: &str) -> napi::Result<bitcoin::Network> {
    match network.to_lowercase().as_str() {
        "bitcoin" | "mainnet" => Ok(bitcoin::Network::Bitcoin),
        "testnet" | "testnet3" => Ok(bitcoin::Network::Testnet),
        "signet" => Ok(bitcoin::Network::Signet),
        "regtest" => Ok(bitcoin::Network::Regtest),
        other => Err(napi::Error::from_reason(format!(
            "Unknown network: {}. Use bitcoin, testnet, signet, or regtest",
            other
        ))),
    }
}

/// Generate a new BIP39 mnemonic phrase.
///
/// `word_count` must be 12 or 24.
#[napi]
pub fn generate_mnemonic(word_count: u32) -> napi::Result<String> {
    let m = mnemonic::generate_mnemonic(word_count as usize)
        .map_err(|e| napi::Error::from_reason(e.to_string()))?;
    Ok(m.to_string())
}

/// Validate a BIP39 mnemonic phrase.
///
/// Returns `true` if the phrase is valid, `false` otherwise.
#[napi]
pub fn validate_mnemonic(phrase: String) -> bool {
    mnemonic::from_phrase(&phrase).is_ok()
}

/// Derive a 64-byte BIP39 seed from a mnemonic phrase and passphrase.
///
/// The passphrase can be empty for no passphrase.
#[napi]
pub fn mnemonic_to_seed(phrase: String, passphrase: String) -> napi::Result<Vec<u8>> {
    let m = mnemonic::from_phrase(&phrase)
        .map_err(|e| napi::Error::from_reason(e.to_string()))?;
    let seed = mnemonic::to_seed(&m, &passphrase);
    Ok(seed.to_vec())
}

/// Derive a P2WPKH (native SegWit, bech32) receive address from a seed.
///
/// Uses BIP84 derivation: m/84'/coin'/account'/0/index
///
/// - `seed`: 64-byte BIP39 seed (from `mnemonic_to_seed`)
/// - `network`: "bitcoin", "testnet", "signet", or "regtest"
/// - `account`: BIP44 account index (usually 0)
/// - `index`: address index
#[napi]
pub fn derive_address(
    seed: Vec<u8>,
    network: String,
    account: u32,
    index: u32,
) -> napi::Result<String> {
    let net = parse_network(&network)?;
    let addr = address::derive_p2wpkh_address(&seed, net, account, false, index)
        .map_err(|e| napi::Error::from_reason(e.to_string()))?;
    Ok(addr.to_string())
}

/// Create a BDK wallet from BIP84 descriptors (validation only).
///
/// Returns "ok" if the descriptors are valid and a wallet can be created.
/// This is a lightweight check -- the wallet is not persisted.
///
/// - `descriptor`: external (receive) descriptor, e.g. `wpkh(tprv.../84'/1'/0'/0/*)`
/// - `change_descriptor`: internal (change) descriptor
/// - `network`: "bitcoin", "testnet", "signet", or "regtest"
#[napi]
pub fn create_wallet(
    descriptor: String,
    change_descriptor: String,
    network: String,
) -> napi::Result<String> {
    let net = parse_network(&network)?;
    wallet::create_wallet(&descriptor, &change_descriptor, net)
        .map_err(|e| napi::Error::from_reason(e.to_string()))?;
    Ok("ok".to_string())
}

/// Get the wallet's confirmed+unconfirmed balance in satoshis.
///
/// Creates a temporary wallet, performs a full Esplora sync, and returns
/// the total balance. This is a blocking call suitable for napi-rs
/// (unlike WASM which cannot use blocking I/O).
///
/// - `descriptor`: external (receive) descriptor
/// - `change_descriptor`: internal (change) descriptor
/// - `network`: "bitcoin", "testnet", "signet", or "regtest"
/// - `esplora_url`: Esplora API base URL, e.g. `https://mempool.space/testnet/api`
#[napi]
pub fn get_balance_sats(
    descriptor: String,
    change_descriptor: String,
    network: String,
    esplora_url: String,
) -> napi::Result<i64> {
    let net = parse_network(&network)?;
    let mut w = wallet::create_wallet(&descriptor, &change_descriptor, net)
        .map_err(|e| napi::Error::from_reason(e.to_string()))?;
    wallet::sync_wallet(&mut w, &esplora_url)
        .map_err(|e| napi::Error::from_reason(e.to_string()))?;
    let balance = wallet::get_balance(&w);
    Ok(balance.total().to_sat() as i64)
}
