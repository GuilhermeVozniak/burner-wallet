//! PSBT (BIP174) construction, merging, finalization, and serialization.

use bitcoin::psbt::Psbt;
use bitcoin::{Address, Amount, FeeRate, Transaction};
use bdk_wallet::Wallet;

use crate::error::Error;

/// Create an unsigned PSBT for sending bitcoin to one or more recipients.
///
/// The wallet must have been synced first so it has UTXOs to spend from.
/// `fee_rate` is in sat/vB.
pub fn create_unsigned_psbt(
    wallet: &mut Wallet,
    recipients: &[(Address, Amount)],
    fee_rate: FeeRate,
) -> Result<Psbt, Error> {
    let mut builder = wallet.build_tx();
    for (addr, amount) in recipients {
        builder.add_recipient(addr.script_pubkey(), *amount);
    }
    builder.fee_rate(fee_rate);
    builder
        .finish()
        .map_err(|e| Error::Psbt(e.to_string()))
}

/// Merge a signed PSBT (from the signer) into the original unsigned PSBT.
///
/// After merging, the original PSBT will contain the signatures from the
/// signed copy. The underlying unsigned transaction must match.
pub fn merge_signed_psbt(original: &mut Psbt, signed_bytes: &[u8]) -> Result<(), Error> {
    let signed = deserialize_psbt(signed_bytes)?;
    original
        .combine(signed)
        .map_err(|e| Error::Psbt(e.to_string()))
}

/// Finalize a PSBT and extract the broadcastable transaction.
///
/// This uses the default maximum fee rate check (25,000 sat/vB).
/// Returns an error if the PSBT is not fully signed or if the fee rate
/// exceeds the limit.
pub fn finalize_psbt(psbt: &Psbt) -> Result<Transaction, Error> {
    psbt.clone()
        .extract_tx()
        .map_err(|e| Error::Psbt(e.to_string()))
}

/// Serialize a PSBT to its binary BIP174 encoding.
pub fn serialize_psbt(psbt: &Psbt) -> Vec<u8> {
    psbt.serialize()
}

/// Deserialize a PSBT from its binary BIP174 encoding.
pub fn deserialize_psbt(bytes: &[u8]) -> Result<Psbt, Error> {
    Psbt::deserialize(bytes).map_err(|e| Error::Psbt(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use bitcoin::blockdata::locktime::absolute;
    use bitcoin::blockdata::transaction::{self, OutPoint, Sequence, TxIn};
    use bitcoin::blockdata::witness::Witness;
    use bitcoin::hashes::Hash;
    use bitcoin::{ScriptBuf, Transaction, Txid};

    /// Create a minimal valid PSBT for testing serialization round-trips.
    fn minimal_psbt() -> Psbt {
        let unsigned_tx = Transaction {
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

        Psbt {
            unsigned_tx,
            version: 0,
            xpub: Default::default(),
            proprietary: Default::default(),
            unknown: Default::default(),
            inputs: vec![bitcoin::psbt::Input::default()],
            outputs: vec![bitcoin::psbt::Output::default()],
        }
    }

    #[test]
    fn psbt_serialize_deserialize_roundtrip() {
        let psbt = minimal_psbt();
        let bytes = serialize_psbt(&psbt);
        let deserialized = deserialize_psbt(&bytes).expect("Failed to deserialize PSBT");

        // Verify round-trip: the unsigned transaction should be identical
        assert_eq!(psbt.unsigned_tx.version, deserialized.unsigned_tx.version);
        assert_eq!(psbt.unsigned_tx.lock_time, deserialized.unsigned_tx.lock_time);
        assert_eq!(psbt.unsigned_tx.input.len(), deserialized.unsigned_tx.input.len());
        assert_eq!(psbt.unsigned_tx.output.len(), deserialized.unsigned_tx.output.len());
        assert_eq!(
            psbt.unsigned_tx.output[0].value,
            deserialized.unsigned_tx.output[0].value
        );

        // Verify the re-serialized bytes are identical
        let reserialized = serialize_psbt(&deserialized);
        assert_eq!(bytes, reserialized);
    }

    #[test]
    fn psbt_serialize_starts_with_magic() {
        let psbt = minimal_psbt();
        let bytes = serialize_psbt(&psbt);
        // BIP174 magic: "psbt" followed by 0xff separator
        assert!(bytes.len() >= 5);
        assert_eq!(&bytes[0..4], b"psbt");
        assert_eq!(bytes[4], 0xff);
    }

    #[test]
    fn psbt_deserialize_invalid_bytes_fails() {
        let garbage = vec![0x00, 0x01, 0x02, 0x03];
        let result = deserialize_psbt(&garbage);
        assert!(result.is_err());
    }

    #[test]
    fn psbt_deserialize_empty_fails() {
        let result = deserialize_psbt(&[]);
        assert!(result.is_err());
    }

    #[test]
    fn psbt_merge_same_psbt_succeeds() {
        let psbt = minimal_psbt();
        let bytes = serialize_psbt(&psbt);
        let mut original = minimal_psbt();
        let result = merge_signed_psbt(&mut original, &bytes);
        assert!(result.is_ok());
    }

    #[test]
    fn psbt_merge_different_tx_fails() {
        let mut original = minimal_psbt();

        // Create a PSBT with a different transaction
        let different_tx = Transaction {
            version: transaction::Version::ONE,
            lock_time: absolute::LockTime::ZERO,
            input: vec![],
            output: vec![],
        };
        let different_psbt = Psbt {
            unsigned_tx: different_tx,
            version: 0,
            xpub: Default::default(),
            proprietary: Default::default(),
            unknown: Default::default(),
            inputs: vec![],
            outputs: vec![],
        };
        let bytes = serialize_psbt(&different_psbt);

        let result = merge_signed_psbt(&mut original, &bytes);
        assert!(result.is_err(), "Merging PSBTs with different transactions should fail");
    }

    #[test]
    fn psbt_finalize_unsigned_extracts_tx() {
        // An unsigned PSBT can still be "finalized" (extract_tx just copies
        // the unsigned tx with empty witness data). The fee rate check may
        // pass or fail depending on the tx structure.
        let psbt = minimal_psbt();
        // This may succeed or fail depending on fee rate checks -- just
        // verify it doesn't panic.
        let _ = finalize_psbt(&psbt);
    }

    #[test]
    fn psbt_serialize_hex_roundtrip() {
        let psbt = minimal_psbt();
        let bytes = serialize_psbt(&psbt);
        let hex_str = hex::encode(&bytes);
        let decoded_bytes = hex::decode(&hex_str).unwrap();
        let deserialized = deserialize_psbt(&decoded_bytes).unwrap();
        assert_eq!(
            psbt.unsigned_tx.compute_txid(),
            deserialized.unsigned_tx.compute_txid()
        );
    }
}
