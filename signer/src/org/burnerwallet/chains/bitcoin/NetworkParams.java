package org.burnerwallet.chains.bitcoin;

/**
 * Bitcoin network constants for mainnet and testnet.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class NetworkParams {

    /** BIP32 extended private key version bytes for mainnet (xprv). */
    public static final byte[] MAINNET_XPRV_VERSION = {
        (byte) 0x04, (byte) 0x88, (byte) 0xAD, (byte) 0xE4
    };

    /** BIP32 extended public key version bytes for mainnet (xpub). */
    public static final byte[] MAINNET_XPUB_VERSION = {
        (byte) 0x04, (byte) 0x88, (byte) 0xB2, (byte) 0x1E
    };

    /** BIP32 extended private key version bytes for testnet (tprv). */
    public static final byte[] TESTNET_XPRV_VERSION = {
        (byte) 0x04, (byte) 0x35, (byte) 0x83, (byte) 0x94
    };

    /** BIP32 extended public key version bytes for testnet (tpub). */
    public static final byte[] TESTNET_XPUB_VERSION = {
        (byte) 0x04, (byte) 0x35, (byte) 0x87, (byte) 0xCF
    };

    /** Bech32 human-readable part for mainnet. */
    public static final String MAINNET_BECH32_HRP = "bc";

    /** Bech32 human-readable part for testnet. */
    public static final String TESTNET_BECH32_HRP = "tb";

    /** BIP44 coin type for Bitcoin mainnet. */
    public static final int MAINNET_COIN_TYPE = 0;

    /** BIP44 coin type for Bitcoin testnet. */
    public static final int TESTNET_COIN_TYPE = 1;

    private NetworkParams() {
        // prevent instantiation
    }
}
