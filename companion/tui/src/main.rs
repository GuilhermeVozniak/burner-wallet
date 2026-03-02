//! Burner Wallet Companion TUI — terminal interface for the online companion.

use burner_companion_core::{mnemonic, address, Network};
use clap::Parser;

#[derive(Parser)]
#[command(name = "burner-companion", about = "Burner Wallet companion TUI")]
struct Cli {
    /// Bitcoin network: mainnet, testnet, signet
    #[arg(long, default_value = "testnet")]
    network: String,
}

fn main() {
    let cli = Cli::parse();
    let network = match cli.network.as_str() {
        "mainnet" => Network::Bitcoin,
        "signet" => Network::Signet,
        _ => Network::Testnet,
    };

    println!("Burner Wallet Companion v0.1.0");
    println!("Network: {}", cli.network);
    println!();

    let m = mnemonic::generate_mnemonic(12).unwrap();
    println!("Generated mnemonic: {}", m);
    println!();

    let seed = mnemonic::to_seed(&m, "");
    let addr = address::derive_p2wpkh_address(&seed, network, 0, false, 0).unwrap();
    println!("First receive address: {}", addr);
    println!();
    println!("WARNING: This is a demo. Do not send real funds.");
}
