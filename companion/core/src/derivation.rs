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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bip84_mainnet_account_0() {
        let path = bip84_account(Network::Bitcoin, 0);
        assert_eq!(format!("m/{}", path), "m/84'/0'/0'");
    }

    #[test]
    fn bip84_testnet_account_0() {
        let path = bip84_account(Network::Testnet, 0);
        assert_eq!(format!("m/{}", path), "m/84'/1'/0'");
    }

    #[test]
    fn bip84_receive_address_path() {
        let path = bip84_address(Network::Bitcoin, 0, false, 0);
        assert_eq!(format!("m/{}", path), "m/84'/0'/0'/0/0");
    }

    #[test]
    fn bip84_change_address_path() {
        let path = bip84_address(Network::Bitcoin, 0, true, 0);
        assert_eq!(format!("m/{}", path), "m/84'/0'/0'/1/0");
    }

    #[test]
    fn bip84_receive_index_5() {
        let path = bip84_address(Network::Testnet, 0, false, 5);
        assert_eq!(format!("m/{}", path), "m/84'/1'/0'/0/5");
    }

    #[test]
    fn bip44_mainnet_account_0() {
        let path = bip44_account(Network::Bitcoin, 0);
        assert_eq!(format!("m/{}", path), "m/44'/0'/0'");
    }

    #[test]
    fn bip44_testnet_account_1() {
        let path = bip44_account(Network::Testnet, 1);
        assert_eq!(format!("m/{}", path), "m/44'/1'/1'");
    }

    #[test]
    fn coin_type_signet_is_testnet() {
        let path = bip84_account(Network::Signet, 0);
        assert_eq!(format!("m/{}", path), "m/84'/1'/0'");
    }
}
