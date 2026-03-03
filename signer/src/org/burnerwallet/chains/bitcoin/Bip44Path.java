package org.burnerwallet.chains.bitcoin;

/**
 * BIP44 and BIP84 derivation path builders.
 *
 * BIP44 defines the standard hierarchy for deterministic wallets:
 *   m / purpose' / coin_type' / account' / change / address_index
 *
 * BIP84 uses the same structure with purpose = 84 for native SegWit (P2WPKH).
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class Bip44Path {

    private Bip44Path() {
        // prevent instantiation
    }

    /**
     * Build a BIP84 account-level path: m/84'/coin'/account'
     *
     * @param testnet true for testnet (coin type 1), false for mainnet (coin type 0)
     * @param account account index (typically 0)
     * @return derivation path string
     */
    public static String bip84Account(boolean testnet, int account) {
        int coinType = testnet ? NetworkParams.TESTNET_COIN_TYPE : NetworkParams.MAINNET_COIN_TYPE;
        return "m/84'/" + coinType + "'/" + account + "'";
    }

    /**
     * Build a BIP84 address-level path: m/84'/coin'/account'/change/index
     *
     * @param testnet true for testnet (coin type 1), false for mainnet (coin type 0)
     * @param account account index (typically 0)
     * @param change  true for change addresses (internal chain), false for receive addresses
     * @param index   address index within the chain
     * @return derivation path string
     */
    public static String bip84Address(boolean testnet, int account, boolean change, int index) {
        return bip84Account(testnet, account) + "/" + (change ? 1 : 0) + "/" + index;
    }

    /**
     * Build a BIP44 account-level path: m/44'/coin'/account'
     *
     * @param testnet true for testnet (coin type 1), false for mainnet (coin type 0)
     * @param account account index (typically 0)
     * @return derivation path string
     */
    public static String bip44Account(boolean testnet, int account) {
        int coinType = testnet ? NetworkParams.TESTNET_COIN_TYPE : NetworkParams.MAINNET_COIN_TYPE;
        return "m/44'/" + coinType + "'/" + account + "'";
    }

    /**
     * Build a BIP44 address-level path: m/44'/coin'/account'/change/index
     *
     * @param testnet true for testnet (coin type 1), false for mainnet (coin type 0)
     * @param account account index (typically 0)
     * @param change  true for change addresses (internal chain), false for receive addresses
     * @param index   address index within the chain
     * @return derivation path string
     */
    public static String bip44Address(boolean testnet, int account, boolean change, int index) {
        return bip44Account(testnet, account) + "/" + (change ? 1 : 0) + "/" + index;
    }
}
