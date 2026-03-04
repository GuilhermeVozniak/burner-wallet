//! Wallet management: create BDK wallets, sync via Esplora, query balance.

use bdk_wallet::{Balance, Wallet};
use bitcoin::Network;

use crate::error::Error;

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
/// Performs a full scan with a stop gap of 5 and 5 parallel requests.
/// After syncing, the wallet's balance and UTXO set will be up to date.
pub fn sync_wallet(wallet: &mut Wallet, esplora_url: &str) -> Result<(), Error> {
    use bdk_esplora::EsploraExt;

    let client = bdk_esplora::esplora_client::Builder::new(esplora_url).build_blocking();
    let request = wallet.start_full_scan().build();
    let update = client
        .full_scan(request, 5, 5)
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
        let wallet =
            create_wallet(TESTNET_EXTERNAL, TESTNET_INTERNAL, Network::Testnet).unwrap();
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
}
