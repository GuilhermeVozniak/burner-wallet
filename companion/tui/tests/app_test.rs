//! Unit tests for the companion TUI application state machine.
//!
//! These tests exercise the App struct's screen transitions, wallet
//! initialization, and multi-step send/receive flows without requiring
//! a terminal or network access.

use bdk_wallet::KeychainKind;
use bitcoin::Network;
use burner_companion_tui::app::{App, Screen, MAX_FEE_RATE_SAT_VB};

const VALID_MNEMONIC: &str =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
const ESPLORA_URL: &str = "https://mempool.space/testnet/api";

/// Signed PSBT produced by the Java ME signer (mainnet), from
/// protocol/vectors/psbt-signing.json.
fn vector_signed_psbt() -> String {
    let v: serde_json::Value =
        serde_json::from_str(include_str!("../../../protocol/vectors/psbt-signing.json"))
            .expect("psbt-signing.json is valid JSON");
    v["signed_psbt"].as_str().unwrap().to_string()
}

fn new_app() -> App {
    App::new(Network::Testnet, ESPLORA_URL.to_string(), None)
}

fn app_with_wallet() -> App {
    App::new(
        Network::Testnet,
        ESPLORA_URL.to_string(),
        Some(VALID_MNEMONIC),
    )
}

// ---- Construction & Initialization ----

#[test]
fn new_app_starts_on_welcome_screen() {
    let app = new_app();
    assert_eq!(app.screen, Screen::Welcome);
    assert!(!app.should_quit);
    assert!(app.bdk_wallet.is_none());
    assert!(app.wallet_fingerprint.is_empty());
}

#[test]
fn new_app_with_mnemonic_goes_to_wallet() {
    let app = app_with_wallet();
    assert_eq!(app.screen, Screen::Wallet);
    assert!(app.bdk_wallet.is_some());
    assert!(!app.wallet_fingerprint.is_empty());
}

#[test]
fn wallet_is_watch_only() {
    // The online companion must never hold a signing key.
    let app = app_with_wallet();
    let w = app.bdk_wallet.as_ref().unwrap();
    assert!(w.get_signers(KeychainKind::External).ids().is_empty());
    assert!(w.get_signers(KeychainKind::Internal).ids().is_empty());
    // Descriptor carries key origin for the signer: [fingerprint/84h/1h/0h]
    let desc = w.public_descriptor(KeychainKind::External).to_string();
    assert!(
        desc.starts_with("wpkh(["),
        "expected origin info, got {}",
        desc
    );
    assert!(desc.contains("/84h/1h/0h]") || desc.contains("/84'/1'/0']"));
    assert!(!desc.contains("tprv") && !desc.contains("xprv"));
}

#[test]
fn wallet_fingerprint_matches_bip32_vector() {
    // Master fingerprint of the "abandon ... about" seed is 73c5da0a.
    let app = app_with_wallet();
    assert_eq!(app.wallet_fingerprint, "73c5da0a");
}

#[test]
fn new_app_stores_network_and_esplora() {
    let app = new_app();
    assert_eq!(app.network, Network::Testnet);
    assert_eq!(app.esplora_url, ESPLORA_URL);
}

#[test]
fn init_wallet_from_invalid_phrase_stays_on_welcome() {
    let mut app = new_app();
    app.init_wallet_from_phrase("not a valid mnemonic");
    assert_eq!(app.screen, Screen::Welcome);
    assert!(app.bdk_wallet.is_none());
    assert!(app.status_message.contains("Invalid mnemonic"));
}

#[test]
fn init_wallet_from_valid_phrase_transitions_to_wallet() {
    let mut app = new_app();
    app.init_wallet_from_phrase(VALID_MNEMONIC);
    assert_eq!(app.screen, Screen::Wallet);
    assert!(app.bdk_wallet.is_some());
    assert!(!app.wallet_fingerprint.is_empty());
    assert!(!app.receive_address.is_empty());
    // Cross-impl vector: m/84'/1'/0'/0/0 for this mnemonic
    assert_eq!(
        app.receive_address,
        "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl"
    );
    // Testnet address should start with tb1
    assert!(
        app.receive_address.starts_with("tb1"),
        "Expected tb1 prefix, got: {}",
        app.receive_address
    );
}

#[test]
fn generate_new_wallet_transitions_to_wallet() {
    let mut app = new_app();
    app.generate_new_wallet();
    assert_eq!(app.screen, Screen::Wallet);
    assert!(app.bdk_wallet.is_some());
    assert!(!app.wallet_fingerprint.is_empty());
    assert!(!app.receive_address.is_empty());
    // The freshly generated phrase is shown once for backup.
    assert!(app.status_message.contains("WRITE DOWN"));
}

#[test]
fn mainnet_wallet_produces_bc1_address() {
    let app = App::new(
        Network::Bitcoin,
        "https://mempool.space/api".to_string(),
        Some(VALID_MNEMONIC),
    );
    assert_eq!(app.screen, Screen::Wallet);
    assert!(
        app.receive_address.starts_with("bc1"),
        "Expected bc1 prefix, got: {}",
        app.receive_address
    );
}

// ---- Send Flow ----

#[test]
fn start_send_transitions_to_send_address() {
    let mut app = app_with_wallet();
    app.start_send();
    assert_eq!(app.screen, Screen::SendAddress);
    assert!(app.input_buffer.is_empty());
    assert!(app.send_recipient.is_empty());
}

#[test]
fn confirm_send_address_empty_stays_on_address() {
    let mut app = app_with_wallet();
    app.start_send();
    // input_buffer is empty
    app.confirm_send_address();
    assert_eq!(app.screen, Screen::SendAddress);
    assert!(app.status_message.contains("empty"));
}

#[test]
fn confirm_send_address_valid_transitions_to_amount() {
    let mut app = app_with_wallet();
    app.start_send();
    app.input_buffer = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx".to_string();
    app.confirm_send_address();
    assert_eq!(app.screen, Screen::SendAmount);
    assert_eq!(
        app.send_recipient,
        "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
    );
    assert!(app.input_buffer.is_empty());
}

#[test]
fn confirm_send_address_rejects_wrong_network() {
    // A mainnet address must not be accepted by a testnet wallet.
    let mut app = app_with_wallet();
    app.start_send();
    app.input_buffer = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu".to_string();
    app.confirm_send_address();
    assert_eq!(app.screen, Screen::SendAddress);
    assert!(app.status_message.contains("Invalid address"));
    assert!(app.send_recipient.is_empty());
}

#[test]
fn confirm_send_address_rejects_bad_checksum() {
    let mut app = app_with_wallet();
    app.start_send();
    app.input_buffer = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsy".to_string();
    app.confirm_send_address();
    assert_eq!(app.screen, Screen::SendAddress);
    assert!(app.status_message.contains("Invalid address"));
}

#[test]
fn confirm_send_amount_invalid_stays_on_amount() {
    let mut app = app_with_wallet();
    app.screen = Screen::SendAmount;
    app.input_buffer = "not_a_number".to_string();
    app.confirm_send_amount();
    assert_eq!(app.screen, Screen::SendAmount);
    assert!(app.status_message.contains("Invalid"));
}

#[test]
fn confirm_send_amount_zero_stays_on_amount() {
    let mut app = app_with_wallet();
    app.screen = Screen::SendAmount;
    app.input_buffer = "0".to_string();
    app.confirm_send_amount();
    assert_eq!(app.screen, Screen::SendAmount);
    assert!(app.status_message.contains("Invalid"));
}

#[test]
fn confirm_send_amount_valid_transitions_to_fee_rate() {
    let mut app = app_with_wallet();
    app.screen = Screen::SendAmount;
    app.input_buffer = "10000".to_string();
    app.confirm_send_amount();
    assert_eq!(app.screen, Screen::SendFeeRate);
    assert_eq!(app.send_amount, "10000");
}

#[test]
fn confirm_send_fee_rate_invalid_stays() {
    let mut app = app_with_wallet();
    app.screen = Screen::SendFeeRate;
    app.input_buffer = "abc".to_string();
    app.confirm_send_fee_rate();
    assert_eq!(app.screen, Screen::SendFeeRate);
    assert!(app.status_message.contains("Invalid"));
}

#[test]
fn confirm_send_fee_rate_zero_stays() {
    let mut app = app_with_wallet();
    app.screen = Screen::SendFeeRate;
    app.input_buffer = "0".to_string();
    app.confirm_send_fee_rate();
    assert_eq!(app.screen, Screen::SendFeeRate);
}

#[test]
fn confirm_send_fee_rate_valid_transitions_to_confirm() {
    let mut app = app_with_wallet();
    app.screen = Screen::SendFeeRate;
    app.input_buffer = "5".to_string();
    app.confirm_send_fee_rate();
    assert_eq!(app.screen, Screen::SendConfirm);
    assert_eq!(app.send_fee_rate, "5");
}

#[test]
fn confirm_send_fee_rate_absurd_rejected() {
    let mut app = app_with_wallet();
    app.screen = Screen::SendFeeRate;
    app.input_buffer = (MAX_FEE_RATE_SAT_VB + 1).to_string();
    app.confirm_send_fee_rate();
    assert_eq!(app.screen, Screen::SendFeeRate);
    assert!(app.status_message.contains("safety limit"));
}

#[test]
fn build_send_psbt_without_wallet_shows_error() {
    let mut app = new_app();
    app.screen = Screen::SendConfirm;
    app.send_recipient = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx".to_string();
    app.send_amount = "10000".to_string();
    app.send_fee_rate = "1".to_string();
    app.build_send_psbt();
    assert!(app.status_message.contains("No wallet"));
}

#[test]
fn build_send_psbt_with_invalid_address_shows_error() {
    let mut app = app_with_wallet();
    app.screen = Screen::SendConfirm;
    app.send_recipient = "not_an_address".to_string();
    app.send_amount = "10000".to_string();
    app.send_fee_rate = "1".to_string();
    app.build_send_psbt();
    assert!(app.status_message.contains("Invalid address"));
    assert_eq!(app.screen, Screen::SendAddress);
}

// ---- Receive Flow ----

#[test]
fn start_receive_transitions_to_receive_input() {
    let mut app = app_with_wallet();
    app.start_receive();
    assert_eq!(app.screen, Screen::ReceiveInput);
    assert!(app.input_buffer.is_empty());
}

#[test]
fn process_signed_psbt_empty_shows_error() {
    let mut app = app_with_wallet();
    app.screen = Screen::ReceiveInput;
    app.input_buffer = String::new();
    app.process_signed_psbt();
    assert_eq!(app.screen, Screen::ReceiveInput);
    assert!(app.status_message.contains("empty"));
}

#[test]
fn process_signed_psbt_invalid_hex_shows_error() {
    let mut app = app_with_wallet();
    app.screen = Screen::ReceiveInput;
    app.input_buffer = "not_hex!@#".to_string();
    app.process_signed_psbt();
    assert!(app.status_message.contains("Invalid hex"));
}

#[test]
fn process_signed_psbt_invalid_psbt_bytes_shows_error() {
    let mut app = app_with_wallet();
    app.screen = Screen::ReceiveInput;
    app.input_buffer = "deadbeef".to_string();
    app.process_signed_psbt();
    assert!(app.status_message.contains("deserialization failed"));
}

#[test]
fn process_signed_psbt_without_pending_tx_is_rejected() {
    // A valid signed PSBT that we did not build must never reach the
    // broadcast screen.
    let mut app = app_with_wallet();
    app.screen = Screen::ReceiveInput;
    app.input_buffer = vector_signed_psbt();
    app.process_signed_psbt();
    assert_eq!(app.screen, Screen::ReceiveInput);
    assert!(app.status_message.contains("No pending transaction"));
    assert!(app.receive_tx_hex.is_empty());
}

#[test]
fn process_signed_psbt_mismatching_pending_tx_is_rejected() {
    use bitcoin::blockdata::locktime::absolute;
    use bitcoin::blockdata::transaction;
    use bitcoin::psbt::Psbt;

    // Pending PSBT is a different transaction than the signed one.
    let mut app = app_with_wallet();
    let other_tx = bitcoin::Transaction {
        version: transaction::Version::TWO,
        lock_time: absolute::LockTime::ZERO,
        input: vec![],
        output: vec![],
    };
    app.send_psbt = Some(Psbt::from_unsigned_tx(other_tx).unwrap());
    app.screen = Screen::ReceiveInput;
    app.input_buffer = vector_signed_psbt();
    app.process_signed_psbt();
    assert_eq!(app.screen, Screen::ReceiveInput);
    assert!(app.status_message.contains("does not match"));
    assert!(app.receive_tx_hex.is_empty());
}

#[test]
fn process_signed_psbt_matching_pending_tx_finalizes() {
    // Pending PSBT == unsigned version of the vector; the signed vector
    // must merge, verify, and finalize into a 2-item P2WPKH witness.
    let mut app = App::new(
        Network::Bitcoin,
        "https://mempool.space/api".to_string(),
        Some(VALID_MNEMONIC),
    );
    let signed_bytes = hex::decode(vector_signed_psbt()).unwrap();
    let mut unsigned: bitcoin::psbt::Psbt =
        bitcoin::psbt::Psbt::deserialize(&signed_bytes).unwrap();
    for input in unsigned.inputs.iter_mut() {
        input.partial_sigs.clear();
    }
    app.send_psbt = Some(unsigned);
    app.screen = Screen::ReceiveInput;
    app.input_buffer = vector_signed_psbt();
    app.process_signed_psbt();
    assert_eq!(app.screen, Screen::ReceiveConfirm, "{}", app.status_message);
    assert!(!app.receive_tx_hex.is_empty());
    assert_eq!(app.receive_fee_sats, 100_000);
    assert_eq!(app.receive_outputs.len(), 1);
    assert_eq!(
        app.receive_outputs[0].address,
        "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    );
    assert!(
        app.receive_outputs[0].is_mine,
        "output pays our own address"
    );
    let tx: bitcoin::Transaction =
        bitcoin::consensus::encode::deserialize(&hex::decode(&app.receive_tx_hex).unwrap())
            .unwrap();
    assert_eq!(tx.input[0].witness.len(), 2);
    assert_eq!(app.receive_txid, tx.compute_txid().to_string());
}

#[test]
fn broadcast_without_tx_shows_error() {
    let mut app = app_with_wallet();
    app.receive_tx_hex = String::new();
    app.broadcast_transaction();
    assert!(app.status_message.contains("No transaction"));
}

// ---- Navigation ----

#[test]
fn go_home_returns_to_wallet_screen() {
    let mut app = app_with_wallet();
    app.screen = Screen::SendAddress;
    app.input_buffer = "some input".to_string();
    app.go_home();
    assert_eq!(app.screen, Screen::Wallet);
    assert!(app.input_buffer.is_empty());
}

#[test]
fn go_home_from_any_screen() {
    let screens = [
        Screen::SendAmount,
        Screen::SendFeeRate,
        Screen::SendConfirm,
        Screen::SendDisplay,
        Screen::ReceiveInput,
        Screen::ReceiveConfirm,
        Screen::History,
    ];
    for screen in screens {
        let mut app = app_with_wallet();
        app.screen = screen;
        app.go_home();
        assert_eq!(app.screen, Screen::Wallet);
    }
}

// ---- Sync (without network) ----

#[test]
fn sync_without_wallet_shows_error() {
    let mut app = new_app();
    app.sync_wallet();
    assert!(app.status_message.contains("No wallet"));
}

// ---- Default state ----

#[test]
fn default_fee_rate_is_one() {
    let app = new_app();
    assert_eq!(app.send_fee_rate, "1");
}

#[test]
fn balance_starts_at_zero() {
    let app = app_with_wallet();
    assert_eq!(app.balance_sats, 0);
    assert!(!app.synced);
}

#[test]
fn transactions_empty_on_new_wallet() {
    let app = app_with_wallet();
    assert!(app.transactions.is_empty());
}

#[test]
fn wallet_same_mnemonic_produces_same_address() {
    let app1 = App::new(
        Network::Testnet,
        ESPLORA_URL.to_string(),
        Some(VALID_MNEMONIC),
    );
    let app2 = App::new(
        Network::Testnet,
        ESPLORA_URL.to_string(),
        Some(VALID_MNEMONIC),
    );
    assert_eq!(app1.receive_address, app2.receive_address);
}
