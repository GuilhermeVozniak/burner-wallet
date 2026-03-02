//! Burner Wallet companion core library.
//!
//! Provides Bitcoin functionality shared across all companion frontends:
//! UTXO management, PSBT construction, fee estimation, and transaction
//! broadcasting. Built on `rust-bitcoin` and BDK.

pub mod address;
pub mod derivation;
pub mod error;
pub mod keys;
pub mod mnemonic;
pub mod network;

pub use error::Error;
pub use network::NetworkConfig;

// Re-export bitcoin types that consumers need
pub use bitcoin::Network;
pub use bip39::Mnemonic;
