//! Transaction broadcasting via Esplora.

use bitcoin::{Transaction, Txid};

use crate::error::Error;

/// Broadcast a finalized (fully signed) transaction via an Esplora server.
///
/// Returns the transaction ID on success. The transaction must be fully
/// signed and valid -- Esplora will reject invalid transactions.
pub fn broadcast_tx(tx: &Transaction, esplora_url: &str) -> Result<Txid, Error> {
    let client = bdk_esplora::esplora_client::Builder::new(esplora_url).build_blocking();
    client
        .broadcast(tx)
        .map_err(|e| Error::Network(e.to_string()))?;
    Ok(tx.compute_txid())
}

#[cfg(test)]
mod tests {
    use super::*;
    use bitcoin::blockdata::locktime::absolute;
    use bitcoin::blockdata::transaction::{self, OutPoint, Sequence, TxIn};
    use bitcoin::blockdata::witness::Witness;
    use bitcoin::hashes::Hash;
    use bitcoin::{Amount, ScriptBuf};

    #[test]
    fn broadcast_returns_network_error_for_invalid_url() {
        // We can't actually broadcast without a running Esplora server,
        // but we can verify that the function returns a network error
        // when given an invalid URL.
        let tx = Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint {
                    txid: Txid::from_byte_array([0x01; 32]),
                    vout: 0,
                },
                script_sig: ScriptBuf::new(),
                sequence: Sequence::MAX,
                witness: Witness::default(),
            }],
            output: vec![bitcoin::TxOut {
                value: Amount::from_sat(50_000),
                script_pubkey: ScriptBuf::new(),
            }],
        };

        // Broadcasting to an invalid URL should fail with a network error
        let result = broadcast_tx(&tx, "http://127.0.0.1:1");
        assert!(result.is_err());
        let err = result.unwrap_err();
        let err_str = err.to_string();
        assert!(
            err_str.contains("Network error"),
            "Expected network error, got: {}",
            err_str
        );
    }

    #[test]
    fn txid_is_deterministic() {
        let tx = Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: vec![],
            output: vec![bitcoin::TxOut {
                value: Amount::from_sat(1_000),
                script_pubkey: ScriptBuf::new(),
            }],
        };

        // compute_txid should be deterministic
        let txid1 = tx.compute_txid();
        let txid2 = tx.compute_txid();
        assert_eq!(txid1, txid2);
    }
}
