//! BIP173 bech32 address generation from seeds via BIP84 derivation.

use bitcoin::address::{Address, KnownHrp};
use bitcoin::bip32::{ChildNumber, Xpub};
use bitcoin::secp256k1::Secp256k1;
use bitcoin::Network;
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
    let secp = Secp256k1::new();
    let master = keys::master_xpriv(seed)?;
    let account_path = derivation::bip84_account(network, account);
    let account_xpriv = master.derive_priv(&secp, &account_path)
        .map_err(|e| Error::KeyDerivation(e.to_string()))?;
    let account_xpub = Xpub::from_priv(&secp, &account_xpriv);

    let change_child = ChildNumber::from_normal_idx(if change { 1 } else { 0 }).unwrap();
    let index_child = ChildNumber::from_normal_idx(index).unwrap();
    let addr_xpub: Xpub = account_xpub.derive_pub(&secp, &[change_child, index_child])
        .map_err(|e| Error::KeyDerivation(e.to_string()))?;

    let compressed_pk = addr_xpub.to_pub();
    let hrp = KnownHrp::from(network);
    Ok(Address::p2wpkh(&compressed_pk, hrp))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mnemonic;

    #[test]
    fn p2wpkh_from_mnemonic_testnet() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let m = mnemonic::from_phrase(phrase).unwrap();
        let seed = mnemonic::to_seed(&m, "");
        let addr = derive_p2wpkh_address(&seed, Network::Testnet, 0, false, 0).unwrap();
        let addr_str = addr.to_string();
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
