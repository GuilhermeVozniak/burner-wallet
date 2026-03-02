//! BIP32 HD key derivation: master key from seed, child key derivation.

use bitcoin::bip32::{DerivationPath, Xpriv, Xpub};
use bitcoin::NetworkKind;
use bitcoin::secp256k1::Secp256k1;
use crate::Error;

/// Derive the BIP32 master extended private key from a raw seed.
/// Uses mainnet version bytes (xprv/xpub) for test vector compatibility.
pub fn master_xpriv(seed: &[u8]) -> Result<Xpriv, Error> {
    Xpriv::new_master(NetworkKind::Main, seed)
        .map_err(|e| Error::KeyDerivation(e.to_string()))
}

/// Derive a child extended private key at the given BIP32 path.
/// Path format: "m/0'/1/2'" or "m/44'/0'/0'"
pub fn derive_xpriv(master: &Xpriv, path: &str) -> Result<Xpriv, Error> {
    let secp = Secp256k1::signing_only();
    let path: DerivationPath = path.parse()
        .map_err(|e: bitcoin::bip32::Error| Error::KeyDerivation(e.to_string()))?;
    master.derive_priv(&secp, &path)
        .map_err(|e| Error::KeyDerivation(e.to_string()))
}

/// Convert an extended private key to its public counterpart.
pub fn to_xpub(xpriv: &Xpriv) -> Xpub {
    let secp = Secp256k1::new();
    Xpub::from_priv(&secp, xpriv)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SEED_1: &str = "000102030405060708090a0b0c0d0e0f";

    #[test]
    fn master_key_from_seed_vector_1() {
        let seed = hex::decode(SEED_1).unwrap();
        let master = master_xpriv(&seed).unwrap();
        assert_eq!(
            master.to_string(),
            "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"
        );
    }

    #[test]
    fn master_xpub_from_seed_vector_1() {
        let seed = hex::decode(SEED_1).unwrap();
        let master = master_xpriv(&seed).unwrap();
        let xpub = to_xpub(&master);
        assert_eq!(
            xpub.to_string(),
            "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8"
        );
    }

    #[test]
    fn derive_child_vector_1_m_0h() {
        let seed = hex::decode(SEED_1).unwrap();
        let master = master_xpriv(&seed).unwrap();
        let child = derive_xpriv(&master, "m/0'").unwrap();
        assert_eq!(
            child.to_string(),
            "xprv9uHRZZhk6KAJC1avXpDAp4MDc3sQKNxDiPvvkX8Br5ngLNv1TxvUxt4cV1rGL5hj6KCesnDYUhd7oWgT11eZG7XnxHrnYeSvkzY7d2bhkJ7"
        );
    }

    #[test]
    fn derive_child_vector_1_m_0h_1() {
        let seed = hex::decode(SEED_1).unwrap();
        let master = master_xpriv(&seed).unwrap();
        let child = derive_xpriv(&master, "m/0'/1").unwrap();
        // Expected value from rust-bitcoin's own test suite for BIP32 vector 1 m/0h/1
        assert_eq!(
            child.to_string(),
            "xprv9wTYmMFdV23N2TdNG573QoEsfRrWKQgWeibmLntzniatZvR9BmLnvSxqu53Kw1UmYPxLgboyZQaXwTCg8MSY3H2EU4pWcQDnRnrVA1xe8fs"
        );
    }

    const SEED_2: &str = "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542";

    #[test]
    fn master_key_from_seed_vector_2() {
        let seed = hex::decode(SEED_2).unwrap();
        let master = master_xpriv(&seed).unwrap();
        assert_eq!(
            master.to_string(),
            "xprv9s21ZrQH143K31xYSDQpPDxsXRTUcvj2iNHm5NUtrGiGG5e2DtALGdso3pGz6ssrdK4PFmM8NSpSBHNqPqm55Qn3LqFtT2emdEXVYsCzC2U"
        );
    }

    #[test]
    fn derive_xpub_for_path() {
        let seed = hex::decode(SEED_1).unwrap();
        let master = master_xpriv(&seed).unwrap();
        let child_priv = derive_xpriv(&master, "m/0'").unwrap();
        let child_pub = to_xpub(&child_priv);
        assert_eq!(
            child_pub.to_string(),
            "xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw"
        );
    }
}
