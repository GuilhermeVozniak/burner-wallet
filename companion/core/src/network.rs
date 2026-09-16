//! Network configuration for connecting to Bitcoin nodes.

use bitcoin::Network;
use serde::{Deserialize, Serialize};

/// HTTP timeout for Esplora requests, in seconds.
///
/// esplora-client only applies a timeout when one is set; without it a
/// stalled connection blocks the (single-threaded) caller forever.
pub const ESPLORA_TIMEOUT_SECS: u64 = 30;

/// Build a blocking Esplora client with a request timeout.
///
/// Trailing slashes are trimmed so that `https://host/api/` does not turn
/// into `https://host/api//tx`, which some reverse proxies reject.
pub fn esplora_client(esplora_url: &str) -> bdk_esplora::esplora_client::BlockingClient {
    bdk_esplora::esplora_client::Builder::new(esplora_url.trim_end_matches('/'))
        .timeout(ESPLORA_TIMEOUT_SECS)
        .build_blocking()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NetworkConfig {
    pub network: Network,
    pub esplora_url: String,
}

impl Default for NetworkConfig {
    fn default() -> Self {
        Self {
            network: Network::Testnet,
            esplora_url: "https://mempool.space/testnet/api".to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config_is_testnet() {
        let cfg = NetworkConfig::default();
        assert_eq!(cfg.network, Network::Testnet);
    }

    #[test]
    fn esplora_client_trims_trailing_slash() {
        let client = esplora_client("https://example.invalid/api///");
        assert_eq!(client.url(), "https://example.invalid/api");
    }
}
