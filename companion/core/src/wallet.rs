//! Wallet management: create BDK wallets, sync via Esplora, query balance,
//! and list transactions.

use bdk_wallet::chain::ChainPosition;
use bdk_wallet::{Balance, Wallet};
use bitcoin::{Amount, Network, Txid};

use crate::error::Error;
use crate::network::esplora_client;

/// Number of consecutive unused script pubkeys to scan before stopping.
///
/// Must cover the signer's key search window (`PsbtSigner.MAX_RECEIVE_INDEX`
/// is 19, i.e. 20 receive addresses), otherwise funds received at a higher
/// index than the last used one are invisible to the companion. 20 is also
/// the BIP44 recommended gap limit.
pub const STOP_GAP: usize = 20;

/// Parallel Esplora requests during a full scan.
const PARALLEL_REQUESTS: usize = 5;

/// Summary of a wallet transaction for display purposes.
#[derive(Debug, Clone)]
pub struct TxSummary {
    /// Transaction ID.
    pub txid: Txid,
    /// Amount sent (from our wallet's perspective).
    pub sent: Amount,
    /// Amount received (to our wallet's perspective).
    pub received: Amount,
    /// Net change in sats (received - sent). Negative means outgoing.
    pub net: i64,
    /// Whether the transaction is confirmed.
    pub confirmed: bool,
    /// Confirmation height, if confirmed.
    pub confirmation_height: Option<u32>,
}

/// Create a BDK wallet from BIP84 descriptors without persistence.
///
/// `descriptor` and `change_descriptor` should be BIP84-style wpkh descriptors,
/// e.g. `"wpkh(tprv.../84'/1'/0'/0/*)"`.
pub fn create_wallet(
    descriptor: &str,
    change_descriptor: &str,
    network: Network,
) -> Result<Wallet, Error> {
    Wallet::create(descriptor.to_string(), change_descriptor.to_string())
        .network(network)
        .create_wallet_no_persist()
        .map_err(|e| Error::Bitcoin(e.to_string()))
}

/// Sync wallet UTXOs and transaction history via an Esplora server.
///
/// Performs a full scan with a stop gap of [`STOP_GAP`] and 5 parallel
/// requests. After syncing, the wallet's balance and UTXO set will be up
/// to date. Requests time out after [`crate::network::ESPLORA_TIMEOUT_SECS`].
pub fn sync_wallet(wallet: &mut Wallet, esplora_url: &str) -> Result<(), Error> {
    use bdk_esplora::EsploraExt;

    let client = esplora_client(esplora_url);
    let request = wallet.start_full_scan().build();
    let update = client
        .full_scan(request, STOP_GAP, PARALLEL_REQUESTS)
        .map_err(|e| Error::Network(e.to_string()))?;
    wallet
        .apply_update(update)
        .map_err(|e| Error::Bitcoin(e.to_string()))?;
    Ok(())
}

/// Get the wallet's current balance, broken down by confirmed/unconfirmed.
pub fn get_balance(wallet: &Wallet) -> Balance {
    wallet.balance()
}

/// List wallet transactions as summaries, newest first.
pub fn get_transactions(wallet: &Wallet) -> Vec<TxSummary> {
    let mut txs: Vec<TxSummary> = wallet
        .transactions()
        .map(|wtx| {
            let (sent, received) = wallet.sent_and_received(&wtx.tx_node.tx);
            let net = received.to_sat() as i64 - sent.to_sat() as i64;
            let (confirmed, confirmation_height) = match wtx.chain_position {
                ChainPosition::Confirmed { anchor, .. } => (true, Some(anchor.block_id.height)),
                ChainPosition::Unconfirmed { .. } => (false, None),
            };
            TxSummary {
                txid: wtx.tx_node.txid,
                sent,
                received,
                net,
                confirmed,
                confirmation_height,
            }
        })
        .collect();

    // Sort: unconfirmed first, then by height descending
    txs.sort_by(|a, b| match (a.confirmed, b.confirmed) {
        (false, true) => std::cmp::Ordering::Less,
        (true, false) => std::cmp::Ordering::Greater,
        _ => b.confirmation_height.cmp(&a.confirmation_height),
    });

    txs
}

#[cfg(test)]
mod tests {
    use super::*;

    // Well-known BIP84 testnet descriptors from BDK's own test suite.
    const TESTNET_EXTERNAL: &str = "wpkh(tprv8ZgxMBicQKsPdy6LMhUtFHAgpocR8GC6QmwMSFpZs7h6Eziw3SpThFfczTDh5rW2krkqffa11UpX3XkeTTB2FvzZKWXqPY54Y6Rq4AQ5R8L/84'/1'/0'/0/*)";
    const TESTNET_INTERNAL: &str = "wpkh(tprv8ZgxMBicQKsPdy6LMhUtFHAgpocR8GC6QmwMSFpZs7h6Eziw3SpThFfczTDh5rW2krkqffa11UpX3XkeTTB2FvzZKWXqPY54Y6Rq4AQ5R8L/84'/1'/0'/1/*)";

    #[test]
    fn wallet_create_testnet() {
        let wallet = create_wallet(TESTNET_EXTERNAL, TESTNET_INTERNAL, Network::Testnet);
        assert!(
            wallet.is_ok(),
            "Failed to create testnet wallet: {:?}",
            wallet.err()
        );
    }

    #[test]
    fn wallet_create_regtest() {
        // BDK test descriptors work with regtest as well.
        let wallet = create_wallet(TESTNET_EXTERNAL, TESTNET_INTERNAL, Network::Regtest);
        assert!(
            wallet.is_ok(),
            "Failed to create regtest wallet: {:?}",
            wallet.err()
        );
    }

    #[test]
    fn wallet_balance_starts_at_zero() {
        let wallet = create_wallet(TESTNET_EXTERNAL, TESTNET_INTERNAL, Network::Testnet).unwrap();
        let balance = get_balance(&wallet);
        assert_eq!(balance.total().to_sat(), 0);
    }

    #[test]
    fn wallet_create_invalid_descriptor_fails() {
        let result = create_wallet("invalid", "also-invalid", Network::Testnet);
        assert!(result.is_err());
    }

    #[test]
    fn wallet_reveal_address() {
        // Creating a wallet and revealing the first address should work.
        let mut wallet =
            create_wallet(TESTNET_EXTERNAL, TESTNET_INTERNAL, Network::Testnet).unwrap();
        let addr = wallet.reveal_next_address(bdk_wallet::KeychainKind::External);
        let addr_str = addr.address.to_string();
        assert!(
            addr_str.starts_with("tb1q"),
            "Expected tb1q prefix, got: {}",
            addr_str
        );
    }

    #[test]
    fn wallet_transactions_empty_on_new_wallet() {
        let wallet = create_wallet(TESTNET_EXTERNAL, TESTNET_INTERNAL, Network::Testnet).unwrap();
        let txs = get_transactions(&wallet);
        assert!(txs.is_empty());
    }
}
