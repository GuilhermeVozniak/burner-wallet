//! WASM integration tests using wasm_bindgen_test.
//!
//! These tests run in a simulated browser environment via `wasm-pack test`
//! or natively via `cargo test`.

#![cfg(target_arch = "wasm32")]

use wasm_bindgen_test::*;

use burner_companion_wasm::{
    derive_address, generate_mnemonic, mnemonic_to_seed, validate_mnemonic,
};

// ---------------------------------------------------------------------------
// BIP39 Mnemonic Tests
// ---------------------------------------------------------------------------

#[wasm_bindgen_test]
fn wasm_generate_mnemonic_12_words() {
    let phrase = generate_mnemonic(12).unwrap();
    let words: Vec<&str> = phrase.split_whitespace().collect();
    assert_eq!(words.len(), 12);
}

#[wasm_bindgen_test]
fn wasm_generate_mnemonic_24_words() {
    let phrase = generate_mnemonic(24).unwrap();
    let words: Vec<&str> = phrase.split_whitespace().collect();
    assert_eq!(words.len(), 24);
}

#[wasm_bindgen_test]
fn wasm_generated_mnemonic_is_valid() {
    let phrase = generate_mnemonic(12).unwrap();
    assert!(validate_mnemonic(&phrase));
}

#[wasm_bindgen_test]
fn wasm_validate_known_mnemonic() {
    let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    assert!(validate_mnemonic(phrase));
}

#[wasm_bindgen_test]
fn wasm_validate_rejects_garbage() {
    assert!(!validate_mnemonic("hello world foo bar"));
}

// ---------------------------------------------------------------------------
// Seed Derivation Tests
// ---------------------------------------------------------------------------

#[wasm_bindgen_test]
fn wasm_mnemonic_to_seed_length() {
    let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    let seed = mnemonic_to_seed(phrase, "").unwrap();
    assert_eq!(seed.len(), 64);
}

#[wasm_bindgen_test]
fn wasm_mnemonic_to_seed_passphrase_differs() {
    let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    let seed1 = mnemonic_to_seed(phrase, "").unwrap();
    let seed2 = mnemonic_to_seed(phrase, "password").unwrap();
    assert_ne!(seed1, seed2);
}

// ---------------------------------------------------------------------------
// Address Derivation Tests
// ---------------------------------------------------------------------------

#[wasm_bindgen_test]
fn wasm_derive_address_mainnet() {
    let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    let seed = mnemonic_to_seed(phrase, "").unwrap();
    let addr = derive_address(&seed, "mainnet", 0, 0).unwrap();
    assert_eq!(addr, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu");
}

#[wasm_bindgen_test]
fn wasm_derive_address_testnet() {
    let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    let seed = mnemonic_to_seed(phrase, "").unwrap();
    let addr = derive_address(&seed, "testnet", 0, 0).unwrap();
    assert_eq!(addr, "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl");
}

#[wasm_bindgen_test]
fn wasm_derive_address_different_indices() {
    let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    let seed = mnemonic_to_seed(phrase, "").unwrap();
    let addr0 = derive_address(&seed, "mainnet", 0, 0).unwrap();
    let addr1 = derive_address(&seed, "mainnet", 0, 1).unwrap();
    assert_ne!(addr0, addr1);
}
