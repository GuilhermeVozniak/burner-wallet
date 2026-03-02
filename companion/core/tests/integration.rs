//! End-to-end integration test: mnemonic -> seed -> master key -> BIP84 address

use burner_companion_core::{mnemonic, keys, derivation, address};
use bitcoin::Network;

#[test]
fn full_wallet_derivation_flow() {
    let m = mnemonic::generate_mnemonic(12).unwrap();
    let seed = mnemonic::to_seed(&m, "");

    let master = keys::master_xpriv(&seed).unwrap();
    let master_pub = keys::to_xpub(&master);
    assert_ne!(master.to_string(), "");
    assert_ne!(master_pub.to_string(), "");

    let path = derivation::bip84_account(Network::Testnet, 0);
    assert_eq!(format!("m/{}", path), "m/84'/1'/0'");

    let addr = address::derive_p2wpkh_address(&seed, Network::Testnet, 0, false, 0).unwrap();
    assert!(addr.to_string().starts_with("tb1q"));

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
