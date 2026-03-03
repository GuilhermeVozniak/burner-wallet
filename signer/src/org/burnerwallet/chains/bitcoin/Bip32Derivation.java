package org.burnerwallet.chains.bitcoin;

import java.math.BigInteger;

import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;

/**
 * BIP32 hierarchical deterministic key derivation.
 *
 * Implements master key generation from seed, child key derivation
 * (both hardened and normal), and path-based derivation.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public final class Bip32Derivation {

    /** Bit flag for hardened derivation (bit 31 set). */
    public static final int HARDENED = 0x80000000;

    /** HMAC key for master key generation per BIP32 spec. */
    private static final byte[] BITCOIN_SEED_KEY;
    static {
        // "Bitcoin seed" as bytes
        String s = "Bitcoin seed";
        BITCOIN_SEED_KEY = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            BITCOIN_SEED_KEY[i] = (byte) s.charAt(i);
        }
    }

    private Bip32Derivation() {
        // prevent instantiation
    }

    /**
     * Generate a master extended private key from a seed.
     *
     * Uses HMAC-SHA512 with key "Bitcoin seed" and the seed as data.
     * The left 32 bytes become the private key, the right 32 bytes
     * become the chain code.
     *
     * @param seed the seed bytes (typically 64 bytes from BIP39)
     * @return master extended private key
     * @throws CryptoError if the derived key is invalid
     */
    public static Bip32Key masterFromSeed(byte[] seed) throws CryptoError {
        byte[] hmac = HashUtils.hmacSha512(BITCOIN_SEED_KEY, seed);

        byte[] privateKey = ByteArrayUtils.copyOfRange(hmac, 0, 32);
        byte[] chainCode = ByteArrayUtils.copyOfRange(hmac, 32, 64);

        // Validate the private key
        BigInteger keyInt = new BigInteger(1, privateKey);
        if (keyInt.signum() == 0 || keyInt.compareTo(Secp256k1.getN()) >= 0) {
            throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                "Invalid master key derived from seed");
        }

        // Key data: 0x00 || privateKey (33 bytes)
        byte[] keyData = new byte[33];
        keyData[0] = 0x00;
        System.arraycopy(privateKey, 0, keyData, 1, 32);

        // Master key: depth=0, parent fingerprint=0x00000000, child index=0
        byte[] parentFingerprint = new byte[4];

        return new Bip32Key(true, 0, parentFingerprint, 0, chainCode, keyData);
    }

    /**
     * Derive a child key from a parent key at the given index.
     *
     * For hardened derivation (index >= HARDENED):
     *   data = 0x00 || parentPrivKey(32) || ser32(index)
     *
     * For normal derivation (index < HARDENED):
     *   data = parentPubKey(33) || ser32(index)
     *
     * @param parent the parent extended key (must be private)
     * @param index  the child index (use HARDENED | i for hardened)
     * @return the derived child key
     * @throws CryptoError if derivation fails
     */
    public static Bip32Key deriveChild(Bip32Key parent, int index) throws CryptoError {
        if (!parent.isPrivate()) {
            throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                "Cannot derive child from public key (not yet supported)");
        }

        byte[] data;
        boolean hardened = (index & HARDENED) != 0;

        if (hardened) {
            // data = 0x00 || parentPrivKey(32) || ser32(index)
            data = new byte[37];
            data[0] = 0x00;
            byte[] privKey = parent.getPrivateKeyBytes();
            System.arraycopy(privKey, 0, data, 1, 32);
            ser32(index, data, 33);
        } else {
            // data = parentPubKey(33) || ser32(index)
            byte[] pubKey = parent.getPublicKeyBytes();
            data = new byte[37];
            System.arraycopy(pubKey, 0, data, 0, 33);
            ser32(index, data, 33);
        }

        byte[] hmac = HashUtils.hmacSha512(parent.getChainCode(), data);
        byte[] hmacLeft = ByteArrayUtils.copyOfRange(hmac, 0, 32);
        byte[] hmacRight = ByteArrayUtils.copyOfRange(hmac, 32, 64);

        BigInteger hmacLeftInt = new BigInteger(1, hmacLeft);
        BigInteger n = Secp256k1.getN();

        // Check hmacLeft >= n (invalid, extremely rare)
        if (hmacLeftInt.compareTo(n) >= 0) {
            throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                "Derived key is invalid (hmacLeft >= n), try next index");
        }

        // childKey = (hmacLeft + parentPrivateKey) mod n
        BigInteger parentKeyInt = new BigInteger(1, parent.getPrivateKeyBytes());
        BigInteger childKeyInt = hmacLeftInt.add(parentKeyInt).mod(n);

        // Check childKey == 0 (invalid, extremely rare)
        if (childKeyInt.signum() == 0) {
            throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                "Derived key is zero, try next index");
        }

        // Convert child key to 32-byte big-endian, zero-padded
        byte[] childKeyBytes = toUnsigned32(childKeyInt);

        // Key data: 0x00 || childKeyBytes
        byte[] childKeyData = new byte[33];
        childKeyData[0] = 0x00;
        System.arraycopy(childKeyBytes, 0, childKeyData, 1, 32);

        // Child chain code
        byte[] childChainCode = hmacRight;

        // Parent fingerprint = first 4 bytes of Hash160(parentPublicKey)
        byte[] parentPubKey = parent.getPublicKeyBytes();
        byte[] parentHash160 = HashUtils.hash160(parentPubKey);
        byte[] fingerprint = ByteArrayUtils.copyOfRange(parentHash160, 0, 4);

        return new Bip32Key(true, parent.getDepth() + 1, fingerprint,
            index, childChainCode, childKeyData);
    }

    /**
     * Derive a key at a full path like "m/84'/0'/0'/0/0".
     *
     * @param master the master key (depth 0)
     * @param path   the derivation path string
     * @return the derived key at the given path
     * @throws CryptoError if the path is invalid or derivation fails
     */
    public static Bip32Key derivePath(Bip32Key master, String path) throws CryptoError {
        int[] indices = parsePath(path);
        Bip32Key current = master;
        for (int i = 0; i < indices.length; i++) {
            current = deriveChild(current, indices[i]);
        }
        return current;
    }

    /**
     * Parse a derivation path string into an array of child indices.
     * Hardened indices (marked with ' or h) have bit 31 set.
     *
     * @param path derivation path (e.g., "m/84'/0'/0'")
     * @return array of child indices
     * @throws CryptoError if the path format is invalid
     */
    static int[] parsePath(String path) throws CryptoError {
        if (path == null || path.length() == 0) {
            throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                "Path cannot be null or empty");
        }

        // Must start with 'm'
        if (path.charAt(0) != 'm') {
            throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                "Path must start with 'm'");
        }

        // "m" alone means the master key, no further derivation
        if (path.length() == 1) {
            return new int[0];
        }

        // Must have '/' after 'm'
        if (path.charAt(1) != '/') {
            throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                "Expected '/' after 'm' in path");
        }

        // Split the rest by '/'
        String rest = path.substring(2);
        // Count the number of components
        int count = 1;
        for (int i = 0; i < rest.length(); i++) {
            if (rest.charAt(i) == '/') {
                count++;
            }
        }

        int[] indices = new int[count];
        int idx = 0;
        int start = 0;

        for (int i = 0; i <= rest.length(); i++) {
            if (i == rest.length() || rest.charAt(i) == '/') {
                String component = rest.substring(start, i);
                if (component.length() == 0) {
                    throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                        "Empty path component");
                }

                boolean hard = false;
                if (component.charAt(component.length() - 1) == '\''
                    || component.charAt(component.length() - 1) == 'h') {
                    hard = true;
                    component = component.substring(0, component.length() - 1);
                }

                int value;
                try {
                    value = parseInt(component);
                } catch (Exception e) {
                    throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                        "Invalid path component: " + component);
                }

                if (value < 0) {
                    throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                        "Negative index in path: " + value);
                }

                if (hard) {
                    value = value | HARDENED;
                }

                indices[idx] = value;
                idx++;
                start = i + 1;
            }
        }

        return indices;
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

    /**
     * Convert a BigInteger to a 32-byte unsigned big-endian byte array.
     * Pads with leading zeros or strips the leading sign byte as needed.
     */
    private static byte[] toUnsigned32(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == 32) {
            return bytes;
        } else if (bytes.length > 32) {
            // Strip leading zero byte (sign byte from BigInteger)
            return ByteArrayUtils.copyOfRange(bytes, bytes.length - 32, bytes.length);
        } else {
            // Pad with leading zeros
            byte[] result = new byte[32];
            System.arraycopy(bytes, 0, result, 32 - bytes.length, bytes.length);
            return result;
        }
    }

    /**
     * Parse a non-negative integer from a string.
     * CLDC 1.1 compatible (no Integer.parseInt).
     */
    private static int parseInt(String s) throws CryptoError {
        if (s == null || s.length() == 0) {
            throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                "Cannot parse empty string as integer");
        }
        long result = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                    "Invalid digit in path: " + c);
            }
            result = result * 10 + (c - '0');
            if (result > 2147483647L) {
                throw new CryptoError(CryptoError.ERR_DERIVATION_FAILED,
                    "Path index overflow: " + s);
            }
        }
        return (int) result;
    }
}
