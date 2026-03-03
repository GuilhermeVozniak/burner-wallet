package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.Base58;
import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;

/**
 * Immutable value type holding a BIP32 extended key (xprv or xpub).
 *
 * Stores depth, parent fingerprint, child index, chain code, and key data.
 * Key data is 33 bytes: 0x00 || 32-byte private key for private keys,
 * or 33-byte compressed public key for public keys.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class Bip32Key {

    private boolean isPrivate;
    private int depth;
    private byte[] parentFingerprint; // 4 bytes
    private int childIndex;
    private byte[] chainCode; // 32 bytes
    private byte[] keyData; // 33 bytes: 0x00||privkey or compressed pubkey

    /**
     * Package-private constructor.
     *
     * @param isPrivate         true if this is a private key
     * @param depth             depth in the derivation tree (0 for master)
     * @param parentFingerprint 4-byte fingerprint of the parent key
     * @param childIndex        child index (hardened indices have bit 31 set)
     * @param chainCode         32-byte chain code
     * @param keyData           33 bytes: 0x00||privkey for private, compressed pubkey for public
     */
    Bip32Key(boolean isPrivate, int depth, byte[] parentFingerprint,
             int childIndex, byte[] chainCode, byte[] keyData) {
        this.isPrivate = isPrivate;
        this.depth = depth;
        this.parentFingerprint = parentFingerprint;
        this.childIndex = childIndex;
        this.chainCode = chainCode;
        this.keyData = keyData;
    }

    /**
     * Whether this is a private extended key.
     */
    public boolean isPrivate() {
        return isPrivate;
    }

    /**
     * Depth in the derivation tree (0 for master).
     */
    public int getDepth() {
        return depth;
    }

    /**
     * 4-byte parent fingerprint.
     */
    public byte[] getParentFingerprint() {
        return parentFingerprint;
    }

    /**
     * Child index (hardened indices have bit 31 set).
     */
    public int getChildIndex() {
        return childIndex;
    }

    /**
     * 32-byte chain code.
     */
    public byte[] getChainCode() {
        return chainCode;
    }

    /**
     * 33-byte raw key data.
     * For private keys: 0x00 || 32-byte private key.
     * For public keys: 33-byte compressed public key.
     */
    public byte[] getKeyData() {
        return keyData;
    }

    /**
     * Get the raw 32-byte private key (strips leading 0x00 prefix).
     *
     * @return 32-byte private key
     * @throws CryptoError if this is a public key
     */
    public byte[] getPrivateKeyBytes() throws CryptoError {
        if (!isPrivate) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                "Cannot get private key bytes from a public key");
        }
        return ByteArrayUtils.copyOfRange(keyData, 1, 33);
    }

    /**
     * Get the 33-byte compressed public key.
     * If this is a private key, derives the public key first.
     *
     * @return 33-byte compressed public key
     * @throws CryptoError if key derivation fails
     */
    public byte[] getPublicKeyBytes() throws CryptoError {
        if (!isPrivate) {
            return ByteArrayUtils.copyOf(keyData, keyData.length);
        }
        // Derive public key from private key
        byte[] privKey = getPrivateKeyBytes();
        return Secp256k1.publicKeyFromPrivate(privKey);
    }

    /**
     * Serialize as Base58Check xprv/tprv string.
     *
     * @param testnet true for testnet (tprv), false for mainnet (xprv)
     * @return Base58Check encoded extended private key
     * @throws CryptoError if this is not a private key
     */
    public String toXprv(boolean testnet) throws CryptoError {
        if (!isPrivate) {
            throw new CryptoError(CryptoError.ERR_INVALID_KEY,
                "Cannot serialize public key as xprv");
        }
        byte[] version = testnet
            ? NetworkParams.TESTNET_XPRV_VERSION
            : NetworkParams.MAINNET_XPRV_VERSION;
        return serializeToBase58(version);
    }

    /**
     * Serialize as Base58Check xpub/tpub string.
     * If this is a private key, derives the public key first.
     *
     * @param testnet true for testnet (tpub), false for mainnet (xpub)
     * @return Base58Check encoded extended public key
     * @throws CryptoError if key derivation fails
     */
    public String toXpub(boolean testnet) throws CryptoError {
        byte[] version = testnet
            ? NetworkParams.TESTNET_XPUB_VERSION
            : NetworkParams.MAINNET_XPUB_VERSION;

        if (!isPrivate) {
            return serializeToBase58(version);
        }

        // Need to convert to public key data first
        byte[] pubKeyData = getPublicKeyBytes();
        byte[] payload = new byte[78];
        System.arraycopy(version, 0, payload, 0, 4);
        payload[4] = (byte) depth;
        System.arraycopy(parentFingerprint, 0, payload, 5, 4);
        ser32(childIndex, payload, 9);
        System.arraycopy(chainCode, 0, payload, 13, 32);
        System.arraycopy(pubKeyData, 0, payload, 45, 33);
        return Base58.encodeCheck(payload);
    }

    /**
     * Convert this key to its public key version.
     * If already public, returns a copy.
     *
     * @return public extended key
     * @throws CryptoError if key derivation fails
     */
    public Bip32Key toPublic() throws CryptoError {
        if (!isPrivate) {
            return new Bip32Key(false, depth,
                ByteArrayUtils.copyOf(parentFingerprint, 4),
                childIndex,
                ByteArrayUtils.copyOf(chainCode, 32),
                ByteArrayUtils.copyOf(keyData, 33));
        }

        byte[] pubKeyData = getPublicKeyBytes();
        return new Bip32Key(false, depth,
            ByteArrayUtils.copyOf(parentFingerprint, 4),
            childIndex,
            ByteArrayUtils.copyOf(chainCode, 32),
            pubKeyData);
    }

    /**
     * Securely erase key data by zeroing chain code and key data arrays.
     */
    public void destroy() {
        ByteArrayUtils.zeroFill(chainCode);
        ByteArrayUtils.zeroFill(keyData);
    }

    /**
     * Serialize the key to Base58Check with the given version bytes.
     *
     * Format (78 bytes total before Base58Check):
     * - 4 bytes: version
     * - 1 byte: depth
     * - 4 bytes: parent fingerprint
     * - 4 bytes: child index (big-endian)
     * - 32 bytes: chain code
     * - 33 bytes: key data
     */
    private String serializeToBase58(byte[] version) {
        byte[] payload = new byte[78];
        System.arraycopy(version, 0, payload, 0, 4);
        payload[4] = (byte) depth;
        System.arraycopy(parentFingerprint, 0, payload, 5, 4);
        ser32(childIndex, payload, 9);
        System.arraycopy(chainCode, 0, payload, 13, 32);
        System.arraycopy(keyData, 0, payload, 45, 33);
        return Base58.encodeCheck(payload);
    }

    /**
     * Write a 32-bit integer in big-endian format to the given array at offset.
     */
    private static void ser32(int value, byte[] out, int offset) {
        out[offset]     = (byte) ((value >>> 24) & 0xFF);
        out[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        out[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        out[offset + 3] = (byte) (value & 0xFF);
    }
}
