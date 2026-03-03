package org.burnerwallet.chains.bitcoin;

import org.burnerwallet.core.ByteArrayUtils;
import org.burnerwallet.core.CryptoError;
import org.burnerwallet.core.HashUtils;

/**
 * BIP39 mnemonic generation, validation, and seed derivation.
 *
 * Supports 128-bit (12 words) through 256-bit (24 words) entropy.
 * Uses SHA-256 for checksum and PBKDF2-HMAC-SHA512 for seed derivation.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class Bip39Mnemonic {

    /**
     * Generate a mnemonic sentence from entropy bytes.
     *
     * Supported entropy sizes: 16 bytes (128 bits, 12 words),
     * 20 bytes (160 bits, 15 words), 24 bytes (192 bits, 18 words),
     * 28 bytes (224 bits, 21 words), 32 bytes (256 bits, 24 words).
     *
     * @param entropy raw entropy bytes
     * @return space-separated mnemonic words
     * @throws CryptoError if entropy length is invalid
     */
    public static String generate(byte[] entropy) throws CryptoError {
        int entBits = entropy.length * 8;

        // Validate entropy length: must be 128, 160, 192, 224, or 256 bits
        if (entBits < 128 || entBits > 256 || entBits % 32 != 0) {
            throw new CryptoError(CryptoError.ERR_INVALID_SEED_LENGTH,
                "Entropy must be 128-256 bits in multiples of 32, got " + entBits);
        }

        // CS = ENT / 32 checksum bits
        int csBits = entBits / 32;
        int totalBits = entBits + csBits;
        int wordCount = totalBits / 11;

        // Compute SHA-256 checksum of entropy
        byte[] hash = HashUtils.sha256(entropy);

        // Build combined byte array: entropy bytes + first byte of hash
        // (we only need up to 8 checksum bits, which fits in 1 byte)
        byte[] combined = new byte[entropy.length + 1];
        System.arraycopy(entropy, 0, combined, 0, entropy.length);
        combined[entropy.length] = hash[0];

        // Extract 11-bit groups from the combined bits
        Bip39Wordlist.init();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < wordCount; i++) {
            int index = extract11Bits(combined, i * 11);
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(Bip39Wordlist.getWord(index));
        }

        return sb.toString();
    }

    /**
     * Validate a mnemonic sentence.
     *
     * Checks: word count (12/15/18/21/24), all words in dictionary,
     * and checksum correctness.
     *
     * @param mnemonic space-separated mnemonic words
     * @return true if valid, false otherwise
     */
    public static boolean validate(String mnemonic) {
        // Split into words
        String[] words = splitWords(mnemonic);
        int wordCount = words.length;

        // Must be 12, 15, 18, 21, or 24 words
        if (wordCount != 12 && wordCount != 15 && wordCount != 18
            && wordCount != 21 && wordCount != 24) {
            return false;
        }

        Bip39Wordlist.init();

        // Convert words to 11-bit indices
        int[] indices = new int[wordCount];
        for (int i = 0; i < wordCount; i++) {
            int idx = Bip39Wordlist.indexOf(words[i]);
            if (idx == -1) {
                return false;
            }
            indices[i] = idx;
        }

        // Total bits = wordCount * 11
        // ENT bits = (wordCount * 11) * 32 / 33
        // CS bits = ENT / 32
        int totalBits = wordCount * 11;
        int csBits = totalBits / 33;
        int entBits = totalBits - csBits;
        int entBytes = entBits / 8;

        // Reconstruct the entropy + checksum bits from indices
        // Pack all indices into a byte array as a bit stream
        byte[] bits = new byte[(totalBits + 7) / 8];
        for (int i = 0; i < wordCount; i++) {
            int value = indices[i];
            int bitOffset = i * 11;
            // Write 11 bits of value starting at bitOffset
            for (int b = 0; b < 11; b++) {
                if ((value & (1 << (10 - b))) != 0) {
                    int pos = bitOffset + b;
                    bits[pos / 8] |= (byte) (1 << (7 - (pos % 8)));
                }
            }
        }

        // Extract entropy bytes (first entBytes)
        byte[] entropy = ByteArrayUtils.copyOfRange(bits, 0, entBytes);

        // Compute expected checksum
        byte[] hash = HashUtils.sha256(entropy);

        // Extract actual checksum bits from the bit stream
        // and compare with expected from hash
        int actualCS = 0;
        for (int i = 0; i < csBits; i++) {
            int pos = entBits + i;
            if ((bits[pos / 8] & (1 << (7 - (pos % 8)))) != 0) {
                actualCS |= (1 << (csBits - 1 - i));
            }
        }

        int expectedCS = ((hash[0] & 0xFF) >> (8 - csBits));

        return actualCS == expectedCS;
    }

    /**
     * Derive a 64-byte seed from a mnemonic and passphrase.
     *
     * Uses PBKDF2-HMAC-SHA512 with 2048 iterations.
     * Password = UTF-8 bytes of mnemonic.
     * Salt = UTF-8 bytes of "mnemonic" + passphrase.
     *
     * @param mnemonic   space-separated mnemonic words
     * @param passphrase optional passphrase (use "" for none)
     * @return 64-byte seed
     */
    public static byte[] toSeed(String mnemonic, String passphrase) {
        byte[] password;
        byte[] salt;
        try {
            password = mnemonic.getBytes("UTF-8");
            salt = ("mnemonic" + passphrase).getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 is always available; fall back to default
            password = mnemonic.getBytes();
            salt = ("mnemonic" + passphrase).getBytes();
        }
        return HashUtils.pbkdf2HmacSha512(password, salt, 2048, 64);
    }

    /**
     * Extract an 11-bit value from a byte array at the given bit offset.
     *
     * @param data      byte array
     * @param bitOffset starting bit position (0-based)
     * @return 11-bit unsigned value (0-2047)
     */
    private static int extract11Bits(byte[] data, int bitOffset) {
        // Which byte contains the start of our 11 bits
        int byteIndex = bitOffset / 8;
        int bitIndex = bitOffset % 8;

        // We need at most 3 bytes to extract 11 bits
        // Assemble up to 24 bits from up to 3 consecutive bytes
        int assembled = (data[byteIndex] & 0xFF) << 16;
        if (byteIndex + 1 < data.length) {
            assembled |= (data[byteIndex + 1] & 0xFF) << 8;
        }
        if (byteIndex + 2 < data.length) {
            assembled |= (data[byteIndex + 2] & 0xFF);
        }

        // Shift right to align the 11 bits we want at the LSB
        // The 11 bits start at bitIndex from the MSB of our 24-bit value
        int shift = 24 - 11 - bitIndex;
        return (assembled >> shift) & 0x7FF;
    }

    /**
     * Split a mnemonic string into words by spaces.
     * Java 1.4 compatible (no String.split with regex).
     *
     * @param mnemonic space-separated words
     * @return array of words
     */
    private static String[] splitWords(String mnemonic) {
        // Count words first
        String trimmed = mnemonic.trim();
        if (trimmed.length() == 0) {
            return new String[0];
        }

        int count = 1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == ' ') {
                count++;
            }
        }

        String[] result = new String[count];
        int idx = 0;
        int start = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == ' ') {
                result[idx] = trimmed.substring(start, i);
                idx++;
                start = i + 1;
            }
        }
        result[idx] = trimmed.substring(start);
        return result;
    }
}
