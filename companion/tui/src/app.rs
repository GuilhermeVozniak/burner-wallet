//! Application state machine for the Burner Wallet companion TUI.
//!
//! Manages screen transitions, wallet lifecycle, and user input across
//! all TUI screens: Welcome, Wallet, Send (multi-step), Receive, and History.
//!
//! The companion is *watch-only*: the BDK wallet is built from the BIP84
//! account xpub (with key origin), so it can construct PSBTs and track
//! balances but never holds a signing key. The seed is used once to derive
//! the xpub and is then wiped.

use bdk_wallet::{KeychainKind, Wallet};
use bitcoin::address::NetworkUnchecked;
use bitcoin::bip32::{Xpriv, Xpub};
use bitcoin::psbt::Psbt;
use bitcoin::secp256k1::Secp256k1;
use bitcoin::{Address, Amount, FeeRate, Network};

use burner_companion_core::wallet::TxSummary;
use burner_companion_core::{broadcast, derivation, mnemonic, psbt, wallet};

/// Highest fee rate (sat/vB) the TUI will accept. Anything above this is
/// almost certainly a typo and would burn funds.
pub const MAX_FEE_RATE_SAT_VB: u64 = 1000;

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

/// One output of the transaction about to be broadcast, for display.
#[derive(Debug, Clone, PartialEq)]
pub struct OutputSummary {
    pub address: String,
    pub sats: u64,
    /// True if the output pays back to this wallet (change).
    pub is_mine: bool,
}

/// Application state for the companion TUI.
pub struct App {
    pub screen: Screen,
    pub bdk_wallet: Option<Wallet>,
    pub network: Network,
    pub esplora_url: String,
    /// BIP32 master key fingerprint of the loaded wallet (hex), empty if none.
    pub wallet_fingerprint: String,
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
    /// Absolute fee of the built PSBT in sats.
    pub send_fee_sats: u64,
    /// Change returned to this wallet by the built PSBT, in sats.
    pub send_change_sats: u64,

    // Receive flow state
    pub receive_input: String,
    pub receive_tx_hex: String,
    pub receive_txid: String,
    pub receive_outputs: Vec<OutputSummary>,
    pub receive_fee_sats: u64,

    // Transaction history
    pub transactions: Vec<TxSummary>,
}

/// Overwrite a seed buffer with zeros in a way the optimizer cannot elide.
fn wipe_seed(seed: &mut [u8; 64]) {
    for b in seed.iter_mut() {
        // SAFETY: `b` is a valid, aligned, exclusively borrowed u8.
        unsafe { std::ptr::write_volatile(b, 0) };
    }
}

impl App {
    /// Create a new App with the given network and optional mnemonic phrase.
    pub fn new(network: Network, esplora_url: String, mnemonic_arg: Option<&str>) -> Self {
        let mut app = App {
            screen: Screen::Welcome,
            bdk_wallet: None,
            network,
            esplora_url,
            wallet_fingerprint: String::new(),
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
            send_fee_sats: 0,
            send_change_sats: 0,
            receive_input: String::new(),
            receive_tx_hex: String::new(),
            receive_txid: String::new(),
            receive_outputs: Vec::new(),
            receive_fee_sats: 0,
            transactions: Vec::new(),
        };

        // If a mnemonic was provided (e.g. from --mnemonic-file), initialize immediately
        if let Some(phrase) = mnemonic_arg {
            app.init_wallet_from_phrase(phrase);
        }

        app
    }

    /// Initialize wallet from a mnemonic phrase string.
    pub fn init_wallet_from_phrase(&mut self, phrase: &str) {
        match mnemonic::from_phrase(phrase.trim()) {
            Ok(m) => {
                let seed = mnemonic::to_seed(&m, "");
                self.init_wallet_from_seed(seed);
            }
            Err(e) => {
                self.status_message = format!("Invalid mnemonic: {}", e);
            }
        }
    }

    /// Build a watch-only BDK wallet from the BIP84 account xpub derived
    /// from `seed`, then wipe the seed. No private key is retained.
    fn init_wallet_from_seed(&mut self, mut seed: [u8; 64]) {
        let coin_type = match self.network {
            Network::Bitcoin => 0,
            _ => 1,
        };

        let secp = Secp256k1::new();
        let master = match Xpriv::new_master(self.network, &seed) {
            Ok(m) => m,
            Err(e) => {
                wipe_seed(&mut seed);
                self.status_message = format!("Key derivation error: {}", e);
                return;
            }
        };
        let fingerprint = master.fingerprint(&secp);
        let account_path = derivation::bip84_account(self.network, 0);
        let account_xpriv = match master.derive_priv(&secp, &account_path) {
            Ok(k) => k,
            Err(e) => {
                wipe_seed(&mut seed);
                self.status_message = format!("Key derivation error: {}", e);
                return;
            }
        };
        let account_xpub = Xpub::from_priv(&secp, &account_xpriv);
        wipe_seed(&mut seed);

        // Public descriptors with key origin so the PSBTs we build carry
        // bip32_derivation for the signer and other BIP174 wallets.
        let origin = format!("[{}/84h/{}h/0h]", fingerprint, coin_type);
        let descriptor = format!("wpkh({}{}/0/*)", origin, account_xpub);
        let change_descriptor = format!("wpkh({}{}/1/*)", origin, account_xpub);

        match wallet::create_wallet(&descriptor, &change_descriptor, self.network) {
            Ok(mut w) => {
                let addr_info = w.reveal_next_address(KeychainKind::External);
                self.receive_address = addr_info.address.to_string();
                self.bdk_wallet = Some(w);
                self.wallet_fingerprint = fingerprint.to_string();
                self.screen = Screen::Wallet;
                self.status_message = String::from("Watch-only wallet loaded. Press 'y' to sync.");
            }
            Err(e) => {
                self.status_message = format!("Wallet creation failed: {}", e);
            }
        }
    }

    /// Generate a new 12-word mnemonic and create wallet from it.
    ///
    /// The phrase is placed in `status_message` exactly once so the user can
    /// write it down; it is not stored anywhere else.
    pub fn generate_new_wallet(&mut self) {
        match mnemonic::generate_mnemonic(12) {
            Ok(m) => {
                let seed = mnemonic::to_seed(&m, "");
                self.init_wallet_from_seed(seed);
                if self.bdk_wallet.is_some() {
                    self.status_message =
                        format!("WRITE DOWN THIS MNEMONIC NOW (shown once): {}", m);
                }
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
                    self.transactions = wallet::get_transactions(w);
                    self.synced = true;
                    self.status_message = format!(
                        "Synced successfully. {} transactions.",
                        self.transactions.len()
                    );
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
        self.send_fee_sats = 0;
        self.send_change_sats = 0;
        self.input_buffer.clear();
        self.screen = Screen::SendAddress;
        self.status_message = String::from("Enter recipient address:");
    }

    /// Parse and validate a recipient address for this wallet's network.
    fn parse_recipient(&self, addr: &str) -> Result<Address, String> {
        let unchecked: Address<NetworkUnchecked> = addr
            .parse()
            .map_err(|e| format!("Invalid address: {}", e))?;
        unchecked
            .require_network(self.network)
            .map_err(|_| format!("Invalid address: not a {} address", self.network))
    }

    /// Confirm the send address and move to amount input.
    pub fn confirm_send_address(&mut self) {
        let addr = self.input_buffer.trim().to_string();
        if addr.is_empty() {
            self.status_message = String::from("Address cannot be empty.");
            return;
        }
        if let Err(msg) = self.parse_recipient(&addr) {
            self.status_message = msg;
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
            Ok(rate) if rate > 0 && rate <= MAX_FEE_RATE_SAT_VB => {
                self.send_fee_rate = fee_str;
                self.input_buffer.clear();
                self.screen = Screen::SendConfirm;
                self.status_message =
                    String::from("Review transaction. Press Enter to build PSBT, Esc to cancel.");
            }
            Ok(rate) if rate > MAX_FEE_RATE_SAT_VB => {
                self.status_message = format!(
                    "Invalid fee rate: {} sat/vB exceeds the {} sat/vB safety limit.",
                    rate, MAX_FEE_RATE_SAT_VB
                );
            }
            _ => {
                self.status_message = String::from("Invalid fee rate. Enter a positive integer.");
            }
        }
    }

    /// Build the unsigned PSBT from the send flow data.
    pub fn build_send_psbt(&mut self) {
        // Re-validate the address (defense in depth; the address screen
        // already checked it against the network).
        let addr = match self.parse_recipient(&self.send_recipient) {
            Ok(a) => a,
            Err(msg) => {
                self.status_message = msg;
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

        let fee_rate = match self
            .send_fee_rate
            .parse::<u64>()
            .ok()
            .filter(|r| *r > 0 && *r <= MAX_FEE_RATE_SAT_VB)
            .and_then(FeeRate::from_sat_per_vb)
        {
            Some(f) => f,
            None => {
                self.status_message = String::from("Invalid fee rate");
                self.screen = Screen::SendFeeRate;
                return;
            }
        };

        let amount = Amount::from_sat(sats);

        if let Some(ref mut w) = self.bdk_wallet {
            match psbt::create_unsigned_psbt(w, &[(addr, amount)], fee_rate) {
                Ok(p) => {
                    let bytes = psbt::serialize_psbt(&p);
                    self.send_fee_sats = psbt::psbt_fee(&p).map(|a| a.to_sat()).unwrap_or(0);
                    self.send_change_sats = p
                        .unsigned_tx
                        .output
                        .iter()
                        .filter(|o| w.is_mine(o.script_pubkey.clone()))
                        .map(|o| o.value.to_sat())
                        .sum();
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
        self.receive_outputs.clear();
        self.receive_fee_sats = 0;
        self.input_buffer.clear();
        self.screen = Screen::ReceiveInput;
        self.status_message = if self.send_psbt.is_some() {
            String::from("Paste signed PSBT hex:")
        } else {
            String::from("No pending transaction. Build one with Send first, then paste the signed PSBT here.")
        };
    }

    /// Process the received signed PSBT: merge into the pending unsigned
    /// PSBT (which rejects a different transaction), finalize (which
    /// verifies every signature), and show the result for confirmation.
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

        if let Err(e) = psbt::deserialize_psbt(&signed_bytes) {
            self.status_message = format!("PSBT deserialization failed: {}", e);
            return;
        }

        let Some(original) = self.send_psbt.as_ref() else {
            self.status_message = String::from(
                "No pending transaction to match against. Build a PSBT with Send first.",
            );
            return;
        };

        // Combine enforces that the signed PSBT carries the exact unsigned
        // transaction we built and reviewed.
        let mut merged = original.clone();
        if let Err(e) = psbt::merge_signed_psbt(&mut merged, &signed_bytes) {
            self.status_message =
                format!("Signed PSBT does not match the pending transaction: {}", e);
            return;
        }

        let tx = match psbt::finalize_psbt(&merged) {
            Ok(tx) => tx,
            Err(e) => {
                self.status_message = format!("Finalization failed: {}", e);
                return;
            }
        };

        let fee_sats = psbt::psbt_fee(&merged).map(|a| a.to_sat()).unwrap_or(0);
        let outputs: Vec<OutputSummary> = tx
            .output
            .iter()
            .map(|o| {
                let address = Address::from_script(&o.script_pubkey, self.network)
                    .map(|a| a.to_string())
                    .unwrap_or_else(|_| format!("script:{}", o.script_pubkey.to_hex_string()));
                let is_mine = self
                    .bdk_wallet
                    .as_ref()
                    .map(|w| w.is_mine(o.script_pubkey.clone()))
                    .unwrap_or(false);
                OutputSummary {
                    address,
                    sats: o.value.to_sat(),
                    is_mine,
                }
            })
            .collect();

        self.receive_txid = tx.compute_txid().to_string();
        self.receive_outputs = outputs;
        self.receive_fee_sats = fee_sats;
        self.receive_tx_hex = bitcoin::consensus::encode::serialize_hex(&tx);
        self.receive_input = hex_input;
        self.input_buffer.clear();
        self.screen = Screen::ReceiveConfirm;
        self.status_message = String::from(
            "Signatures verified. Review the outputs, then press Enter to broadcast or Esc to cancel.",
        );
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
                // The pending PSBT is spent; forget it so it cannot be reused.
                self.send_psbt = None;
                self.send_psbt_hex.clear();
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
