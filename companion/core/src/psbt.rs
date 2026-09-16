//! PSBT (BIP174) construction, merging, finalization, and serialization.

use bdk_wallet::miniscript::psbt::PsbtExt;
use bdk_wallet::Wallet;
use bitcoin::psbt::Psbt;
use bitcoin::secp256k1::Secp256k1;
use bitcoin::{Address, Amount, FeeRate, Transaction};

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
    builder.finish().map_err(|e| Error::Psbt(e.to_string()))
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
/// The signer only adds `partial_sigs`; this step turns them into the
/// final witness for every input. Finalization runs miniscript's
/// interpreter check, which verifies each signature against the input's
/// `witness_utxo`, so a PSBT with a missing, malformed, or forged signature
/// is rejected here instead of producing a transaction the network would
/// refuse. Extraction then applies the default maximum fee rate check
/// (25,000 sat/vB).
pub fn finalize_psbt(psbt: &Psbt) -> Result<Transaction, Error> {
    let secp = Secp256k1::verification_only();
    let mut finalized = psbt.clone();
    finalized.finalize_mut(&secp).map_err(|errors| {
        let msgs: Vec<String> = errors.iter().map(|e| e.to_string()).collect();
        Error::Psbt(format!("finalization failed: {}", msgs.join("; ")))
    })?;
    for (i, input) in finalized.inputs.iter().enumerate() {
        if input.final_script_witness.is_none() && input.final_script_sig.is_none() {
            return Err(Error::Psbt(format!(
                "input {} is not finalized (missing signature)",
                i
            )));
        }
    }
    finalized
        .extract_tx()
        .map_err(|e| Error::Psbt(e.to_string()))
}

/// Absolute fee of a PSBT (sum of `witness_utxo`/`non_witness_utxo` amounts
/// minus sum of outputs).
///
/// Fails if any input lacks UTXO information.
pub fn psbt_fee(psbt: &Psbt) -> Result<Amount, Error> {
    psbt.fee().map_err(|e| Error::Psbt(e.to_string()))
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
        assert_eq!(
            psbt.unsigned_tx.lock_time,
            deserialized.unsigned_tx.lock_time
        );
        assert_eq!(
            psbt.unsigned_tx.input.len(),
            deserialized.unsigned_tx.input.len()
        );
        assert_eq!(
            psbt.unsigned_tx.output.len(),
            deserialized.unsigned_tx.output.len()
        );
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
        assert!(
            result.is_err(),
            "Merging PSBTs with different transactions should fail"
        );
    }

    #[test]
    fn psbt_finalize_unsigned_fails() {
        // An unsigned PSBT must NOT finalize into a broadcastable tx.
        let psbt = minimal_psbt();
        let result = finalize_psbt(&psbt);
        assert!(result.is_err(), "unsigned PSBT must not finalize");
    }

    /// Cross-implementation vector: the exact PSBT the Java ME signer
    /// returns (partial_sigs only, no final witness).
    fn signing_vector() -> serde_json::Value {
        serde_json::from_str(include_str!("../../../protocol/vectors/psbt-signing.json"))
            .expect("psbt-signing.json is valid JSON")
    }

    #[test]
    fn psbt_finalize_signer_output_builds_witness() {
        let vector = signing_vector();
        let signed_hex = vector["signed_psbt"].as_str().unwrap();
        let signed = deserialize_psbt(&hex::decode(signed_hex).unwrap()).unwrap();

        // The signer produces partial_sigs only.
        assert_eq!(signed.inputs[0].partial_sigs.len(), 1);
        assert!(signed.inputs[0].final_script_witness.is_none());

        let tx = finalize_psbt(&signed).expect("signer output must finalize");
        assert_eq!(tx.input.len(), 1);
        // P2WPKH witness: <signature> <pubkey>
        assert_eq!(tx.input[0].witness.len(), 2);
        assert_eq!(
            hex::encode(&tx.input[0].witness[1]),
            vector["pubkey"].as_str().unwrap()
        );
        assert_eq!(
            hex::encode(&tx.input[0].witness[0]),
            vector["signature_der_sighash"].as_str().unwrap()
        );
    }

    #[test]
    fn psbt_finalize_rejects_tampered_signature() {
        let vector = signing_vector();
        let signed_hex = vector["signed_psbt"].as_str().unwrap();
        let mut signed = deserialize_psbt(&hex::decode(signed_hex).unwrap()).unwrap();

        // Flip a byte inside the DER signature (keep the sighash byte).
        let (pk, sig) = signed.inputs[0].partial_sigs.iter().next().unwrap();
        let (pk, mut sig) = (*pk, *sig);
        let mut r_bytes = sig.signature.serialize_compact();
        r_bytes[5] ^= 0x01;
        sig.signature = bitcoin::secp256k1::ecdsa::Signature::from_compact(&r_bytes).unwrap();
        signed.inputs[0].partial_sigs.clear();
        signed.inputs[0].partial_sigs.insert(pk, sig);

        assert!(
            finalize_psbt(&signed).is_err(),
            "a forged signature must not finalize"
        );
    }

    #[test]
    fn psbt_finalize_unsigned_vector_fails() {
        let vector = signing_vector();
        let unsigned_hex = vector["unsigned_psbt"].as_str().unwrap();
        let unsigned = deserialize_psbt(&hex::decode(unsigned_hex).unwrap()).unwrap();
        assert!(finalize_psbt(&unsigned).is_err());
    }

    /// Independent cross-implementation check: recompute the BIP143 sighash
    /// with rust-bitcoin from the vector's unsigned PSBT and verify the
    /// Java ME signer's ECDSA signature against it.
    #[test]
    fn signer_vector_sighash_and_signature_verify_with_rust_bitcoin() {
        use bitcoin::secp256k1::{ecdsa::Signature, Message, PublicKey};
        use bitcoin::sighash::{EcdsaSighashType, SighashCache};
        use bitcoin::ScriptBuf;

        let vector = signing_vector();
        let unsigned_hex = vector["unsigned_psbt"].as_str().unwrap();
        let unsigned = deserialize_psbt(&hex::decode(unsigned_hex).unwrap()).unwrap();
        let tx = &unsigned.unsigned_tx;

        assert_eq!(
            tx.compute_txid().to_string(),
            vector["txid"].as_str().unwrap()
        );

        // non_witness_utxo must be the transaction the input spends
        let prev = unsigned.inputs[0].non_witness_utxo.as_ref().unwrap();
        assert_eq!(prev.compute_txid(), tx.input[0].previous_output.txid);
        assert_eq!(
            prev.compute_txid().to_string(),
            vector["prev_txid"].as_str().unwrap()
        );
        let witness_utxo = unsigned.inputs[0].witness_utxo.as_ref().unwrap();
        assert_eq!(prev.output[0], *witness_utxo);

        let script = ScriptBuf::from_hex(vector["witness_utxo_script"].as_str().unwrap()).unwrap();
        let amount = Amount::from_sat(vector["witness_utxo_value"].as_u64().unwrap());
        let mut cache = SighashCache::new(tx);
        let sighash = cache
            .p2wpkh_signature_hash(0, &script, amount, EcdsaSighashType::All)
            .unwrap();
        assert_eq!(
            hex::encode(sighash.to_byte_array()),
            vector["sighash"].as_str().unwrap()
        );

        let sig_bytes = hex::decode(vector["signature_der_sighash"].as_str().unwrap()).unwrap();
        let (der, hash_type) = sig_bytes.split_at(sig_bytes.len() - 1);
        assert_eq!(hash_type, &[0x01], "signer must use SIGHASH_ALL");
        let sig = Signature::from_der(der).unwrap();
        let pk = PublicKey::from_slice(&hex::decode(vector["pubkey"].as_str().unwrap()).unwrap())
            .unwrap();
        let msg = Message::from_digest(sighash.to_byte_array());
        Secp256k1::verification_only()
            .verify_ecdsa(&msg, &sig, &pk)
            .expect("Java ME signature must verify under rust-bitcoin's sighash");
    }

    #[test]
    fn psbt_fee_from_vector() {
        let vector = signing_vector();
        let unsigned_hex = vector["unsigned_psbt"].as_str().unwrap();
        let unsigned = deserialize_psbt(&hex::decode(unsigned_hex).unwrap()).unwrap();
        // 200,000 in, 100,000 out
        assert_eq!(psbt_fee(&unsigned).unwrap().to_sat(), 100_000);
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
