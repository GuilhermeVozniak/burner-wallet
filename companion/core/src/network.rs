//! Network configuration for connecting to Bitcoin nodes.

use bitcoin::Network;
use serde::{Deserialize, Serialize};

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
}
