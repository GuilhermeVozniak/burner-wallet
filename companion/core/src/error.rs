//! Core error types.

use thiserror::Error;

#[derive(Debug, Error)]
pub enum Error {
    #[error("Bitcoin error: {0}")]
    Bitcoin(String),

    #[error("Network error: {0}")]
    Network(String),

    #[error("PSBT error: {0}")]
    Psbt(String),

    #[error("mnemonic error: {0}")]
    Mnemonic(String),

    #[error("key derivation error: {0}")]
    KeyDerivation(String),
}
