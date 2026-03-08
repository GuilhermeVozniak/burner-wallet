//! Unit tests for the companion TUI application state machine.
//!
//! These tests exercise the App struct's screen transitions, wallet
//! initialization, and multi-step send/receive flows without requiring
//! a terminal or network access.

use bitcoin::Network;
use burner_companion_tui::app::{App, Screen};

const VALID_MNEMONIC: &str =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
const ESPLORA_URL: &str = "https://mempool.space/testnet/api";

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
    assert!(app.mnemonic_phrase.is_none());
}

#[test]
fn new_app_with_mnemonic_goes_to_wallet() {
    let app = app_with_wallet();
    assert_eq!(app.screen, Screen::Wallet);
    assert!(app.bdk_wallet.is_some());
    assert!(app.mnemonic_phrase.is_some());
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
    assert!(app.mnemonic_phrase.is_some());
    assert!(!app.receive_address.is_empty());
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
    assert!(app.mnemonic_phrase.is_some());
    assert!(!app.receive_address.is_empty());
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
