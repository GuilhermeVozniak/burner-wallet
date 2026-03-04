//! Application state machine for the Burner Wallet companion TUI.
//!
//! Manages screen transitions, wallet lifecycle, and user input across
//! all TUI screens: Welcome, Wallet, Send (multi-step), Receive, and History.

use bdk_wallet::Wallet;
use bip39::Mnemonic;
use bitcoin::psbt::Psbt;
use bitcoin::{Address, Amount, FeeRate, Network};

use burner_companion_core::{broadcast, mnemonic, psbt, wallet};

/// All screens in the TUI application.
#[derive(Debug, Clone, PartialEq)]
pub enum Screen {
    Welcome,
    Wallet,
    SendAddress,
    SendAmount,
    SendFeeRate,
    SendConfirm,
    SendDisplay,
    ReceiveInput,
    ReceiveConfirm,
    History,
}

/// Application state for the companion TUI.
pub struct App {
    pub screen: Screen,
    pub bdk_wallet: Option<Wallet>,
    pub network: Network,
    pub esplora_url: String,
    pub mnemonic_phrase: Option<Mnemonic>,
    pub seed: Option<[u8; 64]>,
    pub status_message: String,
    pub input_buffer: String,
    pub should_quit: bool,
    pub synced: bool,
    pub balance_sats: u64,
    pub receive_address: String,

    // Send flow state
    pub send_recipient: String,
    pub send_amount: String,
    pub send_fee_rate: String,
    pub send_psbt: Option<Psbt>,
    pub send_psbt_hex: String,

    // Receive flow state
    pub receive_input: String,
    pub receive_tx_hex: String,
    pub receive_txid: String,
}

impl App {
    /// Create a new App with the given network and optional mnemonic phrase.
    pub fn new(network: Network, esplora_url: String, mnemonic_arg: Option<&str>) -> Self {
        let mut app = App {
            screen: Screen::Welcome,
            bdk_wallet: None,
            network,
            esplora_url,
            mnemonic_phrase: None,
            seed: None,
            status_message: String::new(),
            input_buffer: String::new(),
            should_quit: false,
            synced: false,
            balance_sats: 0,
            receive_address: String::new(),
            send_recipient: String::new(),
            send_amount: String::new(),
            send_fee_rate: String::from("1"),
            send_psbt: None,
            send_psbt_hex: String::new(),
            receive_input: String::new(),
            receive_tx_hex: String::new(),
            receive_txid: String::new(),
        };

        // If a mnemonic was provided via CLI, initialize immediately
        if let Some(phrase) = mnemonic_arg {
            app.init_wallet_from_phrase(phrase);
        }

        app
    }

    /// Initialize wallet from a mnemonic phrase string.
    pub fn init_wallet_from_phrase(&mut self, phrase: &str) {
        match mnemonic::from_phrase(phrase) {
            Ok(m) => {
                let seed = mnemonic::to_seed(&m, "");
                self.init_wallet_from_mnemonic(m, seed);
            }
            Err(e) => {
                self.status_message = format!("Invalid mnemonic: {}", e);
            }
        }
    }

    /// Initialize wallet from a generated or parsed mnemonic.
    fn init_wallet_from_mnemonic(&mut self, m: Mnemonic, seed: [u8; 64]) {
        let coin_type = match self.network {
            Network::Bitcoin => 0,
            _ => 1,
        };

        let master = bitcoin::bip32::Xpriv::new_master(self.network, &seed);
        let master = match master {
            Ok(m) => m,
            Err(e) => {
                self.status_message = format!("Key derivation error: {}", e);
                return;
            }
        };

        let descriptor = format!("wpkh({}/84h/{}h/0h/0/*)", master, coin_type);
        let change_descriptor = format!("wpkh({}/84h/{}h/0h/1/*)", master, coin_type);

        match wallet::create_wallet(&descriptor, &change_descriptor, self.network) {
            Ok(mut w) => {
                let addr_info = w.reveal_next_address(bdk_wallet::KeychainKind::External);
                self.receive_address = addr_info.address.to_string();
                self.bdk_wallet = Some(w);
                self.mnemonic_phrase = Some(m);
                self.seed = Some(seed);
                self.screen = Screen::Wallet;
                self.status_message = String::from("Wallet created. Press 'y' to sync.");
            }
            Err(e) => {
                self.status_message = format!("Wallet creation failed: {}", e);
            }
        }
    }

    /// Generate a new 12-word mnemonic and create wallet from it.
    pub fn generate_new_wallet(&mut self) {
        match mnemonic::generate_mnemonic(12) {
            Ok(m) => {
                let seed = mnemonic::to_seed(&m, "");
                self.init_wallet_from_mnemonic(m, seed);
            }
            Err(e) => {
                self.status_message = format!("Mnemonic generation failed: {}", e);
            }
        }
    }

    /// Sync wallet with Esplora. This is blocking.
    pub fn sync_wallet(&mut self) {
        if let Some(ref mut w) = self.bdk_wallet {
            self.status_message = String::from("Syncing...");
            match wallet::sync_wallet(w, &self.esplora_url) {
                Ok(()) => {
                    let balance = wallet::get_balance(w);
                    self.balance_sats = balance.total().to_sat();
                    self.synced = true;
                    self.status_message = String::from("Synced successfully.");
                }
                Err(e) => {
                    self.status_message = format!("Sync failed: {}", e);
                }
            }
        } else {
            self.status_message = String::from("No wallet to sync.");
        }
    }

    /// Navigate to the send address screen.
    pub fn start_send(&mut self) {
        self.send_recipient.clear();
        self.send_amount.clear();
        self.send_fee_rate = String::from("1");
        self.send_psbt = None;
        self.send_psbt_hex.clear();
        self.input_buffer.clear();
        self.screen = Screen::SendAddress;
        self.status_message = String::from("Enter recipient address:");
    }

    /// Confirm the send address and move to amount input.
    pub fn confirm_send_address(&mut self) {
        let addr = self.input_buffer.trim().to_string();
        if addr.is_empty() {
            self.status_message = String::from("Address cannot be empty.");
            return;
        }
        self.send_recipient = addr;
        self.input_buffer.clear();
        self.screen = Screen::SendAmount;
        self.status_message = String::from("Enter amount in sats:");
    }

    /// Confirm the send amount and move to fee rate input.
    pub fn confirm_send_amount(&mut self) {
        let amount_str = self.input_buffer.trim().to_string();
        match amount_str.parse::<u64>() {
            Ok(sats) if sats > 0 => {
                self.send_amount = amount_str;
                self.input_buffer = self.send_fee_rate.clone();
                self.screen = Screen::SendFeeRate;
                self.status_message = format!(
                    "Enter fee rate in sat/vB (current: {}):",
                    self.send_fee_rate
                );
                // Pre-fill with default
                let _ = sats;
            }
            _ => {
                self.status_message = String::from("Invalid amount. Enter a positive integer.");
            }
        }
    }

    /// Confirm the fee rate and move to the confirmation screen.
    pub fn confirm_send_fee_rate(&mut self) {
        let fee_str = self.input_buffer.trim().to_string();
        match fee_str.parse::<u64>() {
            Ok(rate) if rate > 0 => {
                self.send_fee_rate = fee_str;
                self.input_buffer.clear();
                self.screen = Screen::SendConfirm;
                self.status_message =
                    String::from("Review transaction. Press Enter to build PSBT, Esc to cancel.");
                let _ = rate;
            }
            _ => {
                self.status_message = String::from("Invalid fee rate. Enter a positive integer.");
            }
        }
    }

    /// Build the unsigned PSBT from the send flow data.
    pub fn build_send_psbt(&mut self) {
        // Parse the address
        let addr: Address = match self.send_recipient.parse::<Address<_>>() {
            Ok(a) => a.assume_checked(),
            Err(e) => {
                self.status_message = format!("Invalid address: {}", e);
                self.screen = Screen::SendAddress;
                return;
            }
        };

        let sats: u64 = match self.send_amount.parse() {
            Ok(s) => s,
            Err(_) => {
                self.status_message = String::from("Invalid amount");
                self.screen = Screen::SendAmount;
                return;
            }
        };

        let fee_rate_sat_vb: u64 = match self.send_fee_rate.parse() {
            Ok(f) => f,
            Err(_) => {
                self.status_message = String::from("Invalid fee rate");
                self.screen = Screen::SendFeeRate;
                return;
            }
        };

        let amount = Amount::from_sat(sats);
        let fee_rate = FeeRate::from_sat_per_vb(fee_rate_sat_vb).unwrap_or(FeeRate::BROADCAST_MIN);

        if let Some(ref mut w) = self.bdk_wallet {
            match psbt::create_unsigned_psbt(w, &[(addr, amount)], fee_rate) {
                Ok(p) => {
                    let bytes = psbt::serialize_psbt(&p);
                    self.send_psbt_hex = hex::encode(&bytes);
                    self.send_psbt = Some(p);
                    self.screen = Screen::SendDisplay;
                    self.status_message =
                        String::from("PSBT built. Copy hex below and send to signer.");
                }
                Err(e) => {
                    self.status_message = format!("PSBT creation failed: {}", e);
                }
            }
        } else {
            self.status_message = String::from("No wallet available.");
        }
    }

    /// Navigate to the receive (signed PSBT) screen.
    pub fn start_receive(&mut self) {
        self.receive_input.clear();
        self.receive_tx_hex.clear();
        self.receive_txid.clear();
        self.input_buffer.clear();
        self.screen = Screen::ReceiveInput;
        self.status_message = String::from("Paste signed PSBT hex:");
    }

    /// Process the received signed PSBT: merge, finalize, show for confirmation.
    pub fn process_signed_psbt(&mut self) {
        let hex_input = self.input_buffer.trim().to_string();
        if hex_input.is_empty() {
            self.status_message = String::from("PSBT hex cannot be empty.");
            return;
        }

        let signed_bytes = match hex::decode(&hex_input) {
            Ok(b) => b,
            Err(e) => {
                self.status_message = format!("Invalid hex: {}", e);
                return;
            }
        };

        // Try to deserialize and finalize directly (signer returns a fully-signed PSBT)
        match psbt::deserialize_psbt(&signed_bytes) {
            Ok(signed_psbt) => match psbt::finalize_psbt(&signed_psbt) {
                Ok(tx) => {
                    self.receive_tx_hex = bitcoin::consensus::encode::serialize_hex(&tx);
                    self.receive_input = hex_input;
                    self.input_buffer.clear();
                    self.screen = Screen::ReceiveConfirm;
                    self.status_message = String::from(
                        "Transaction finalized. Press Enter to broadcast, Esc to cancel.",
                    );
                }
                Err(e) => {
                    self.status_message = format!("Finalization failed: {}", e);
                }
            },
            Err(e) => {
                self.status_message = format!("PSBT deserialization failed: {}", e);
            }
        }
    }

    /// Broadcast the finalized transaction.
    pub fn broadcast_transaction(&mut self) {
        if self.receive_tx_hex.is_empty() {
            self.status_message = String::from("No transaction to broadcast.");
            return;
        }

        let tx_bytes = match hex::decode(&self.receive_tx_hex) {
            Ok(b) => b,
            Err(e) => {
                self.status_message = format!("Invalid tx hex: {}", e);
                return;
            }
        };

        let tx: bitcoin::Transaction = match bitcoin::consensus::encode::deserialize(&tx_bytes) {
            Ok(t) => t,
            Err(e) => {
                self.status_message = format!("TX deserialization failed: {}", e);
                return;
            }
        };

        match broadcast::broadcast_tx(&tx, &self.esplora_url) {
            Ok(txid) => {
                self.receive_txid = txid.to_string();
                self.status_message = format!("Broadcast OK! txid: {}", txid);
                self.screen = Screen::Wallet;
            }
            Err(e) => {
                self.status_message = format!("Broadcast failed: {}", e);
            }
        }
    }

    /// Navigate back to wallet screen, clearing transient state.
    pub fn go_home(&mut self) {
        self.screen = Screen::Wallet;
        self.input_buffer.clear();
    }
}
