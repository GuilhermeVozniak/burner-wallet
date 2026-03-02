//! BIP39 mnemonic generation and seed derivation.

use bip39::Mnemonic;
use crate::Error;

/// Generate a new BIP39 mnemonic with the given word count (12 or 24).
pub fn generate_mnemonic(word_count: usize) -> Result<Mnemonic, Error> {
    Mnemonic::generate(word_count)
        .map_err(|e| Error::Mnemonic(e.to_string()))
}

/// Parse and validate an existing BIP39 mnemonic phrase.
pub fn from_phrase(phrase: &str) -> Result<Mnemonic, Error> {
    phrase.parse::<Mnemonic>()
        .map_err(|e| Error::Mnemonic(e.to_string()))
}

/// Derive a 64-byte seed from a mnemonic and optional passphrase.
/// Uses PBKDF2-HMAC-SHA512 with 2048 rounds per BIP39 spec.
pub fn to_seed(mnemonic: &Mnemonic, passphrase: &str) -> [u8; 64] {
    mnemonic.to_seed(passphrase)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_12_word_mnemonic() {
        let m = generate_mnemonic(12).unwrap();
        assert_eq!(m.word_count(), 12);
    }

    #[test]
    fn generate_24_word_mnemonic() {
        let m = generate_mnemonic(24).unwrap();
        assert_eq!(m.word_count(), 24);
    }

    #[test]
    fn mnemonic_from_phrase() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let m = from_phrase(phrase).unwrap();
        assert_eq!(m.word_count(), 12);
    }

    #[test]
    fn seed_derivation_no_passphrase() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let m = from_phrase(phrase).unwrap();
        let seed = to_seed(&m, "");
        let expected = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4";
        assert_eq!(hex::encode(seed), expected);
    }

    #[test]
    fn seed_derivation_with_passphrase() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let seed_no_pass = to_seed(&from_phrase(phrase).unwrap(), "");
        let seed_with_pass = to_seed(&from_phrase(phrase).unwrap(), "my passphrase");
        assert_ne!(seed_no_pass, seed_with_pass);
    }

    #[test]
    fn validate_rejects_invalid_phrase() {
        let bad = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon";
        assert!(from_phrase(bad).is_err());
    }

    #[test]
    fn test_vector_2_legal_winner() {
        // TREZOR test vectors use "TREZOR" as passphrase
        let phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow";
        let m = from_phrase(phrase).unwrap();
        let seed = to_seed(&m, "TREZOR");
        let expected = "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6fa457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607";
        assert_eq!(hex::encode(seed), expected);
    }

    #[test]
    fn test_vector_3_letter_advice() {
        let phrase = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above";
        let m = from_phrase(phrase).unwrap();
        let seed = to_seed(&m, "TREZOR");
        let expected = "d71de856f81a8acc65e6fc851a38d4d7ec216fd0796d0a6827a3ad6ed5511a30fa280f12eb2e47ed2ac03b5c462a0358d18d69fe4f985ec81778c1b370b652a8";
        assert_eq!(hex::encode(seed), expected);
    }

    #[test]
    fn test_vector_4_zoo() {
        let phrase = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong";
        let m = from_phrase(phrase).unwrap();
        let seed = to_seed(&m, "TREZOR");
        let expected = "ac27495480225222079d7be181583751e86f571027b0497b5b5d11218e0a8a13332572917f0f8e5a589620c6f15b11c61dee327651a14c34e18231052e48c069";
        assert_eq!(hex::encode(seed), expected);
    }
}
