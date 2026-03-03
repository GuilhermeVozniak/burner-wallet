package org.burnerwallet.chains.bitcoin;

import java.io.InputStream;
import java.io.IOException;

/**
 * BIP39 English wordlist (2048 words).
 *
 * Lazy-loaded from JAR resource "/bip39-english.txt".
 * Uses manual byte-by-byte reading and newline splitting
 * for CLDC 1.1 compatibility (no BufferedReader).
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class Bip39Wordlist {

    private static String[] words;

    /**
     * Load the wordlist from the JAR resource.
     * After calling init(), isLoaded() returns true and
     * getWord()/indexOf() are usable.
     *
     * @throws RuntimeException if the resource cannot be found or read
     */
    public static void init() {
        if (words != null) {
            return;
        }

        InputStream is;
        try {
            Class cls = Class.forName("org.burnerwallet.chains.bitcoin.Bip39Wordlist");
            is = cls.getResourceAsStream("/bip39-english.txt");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot load Bip39Wordlist class");
        }
        if (is == null) {
            throw new RuntimeException("BIP39 wordlist resource not found");
        }

        try {
            String[] result = new String[2048];
            int wordIndex = 0;

            // Read the entire stream into a byte array.
            // The file is ~13 KB, safe to read in full.
            byte[] buf = new byte[16384];
            int totalRead = 0;
            int bytesRead;
            while ((bytesRead = is.read(buf, totalRead, buf.length - totalRead)) != -1) {
                totalRead += bytesRead;
                if (totalRead >= buf.length) {
                    break;
                }
            }

            // Parse line by line: split on '\n' (0x0A)
            int lineStart = 0;
            for (int i = 0; i < totalRead; i++) {
                if (buf[i] == (byte) 0x0A || i == totalRead - 1) {
                    int lineEnd = i;
                    // Handle \r\n
                    if (lineEnd > lineStart && buf[lineEnd - 1] == (byte) 0x0D) {
                        lineEnd--;
                    }
                    // If we're at the last byte and it's not a newline, include it
                    if (i == totalRead - 1 && buf[i] != (byte) 0x0A) {
                        lineEnd = totalRead;
                    }
                    if (lineEnd > lineStart && wordIndex < 2048) {
                        result[wordIndex] = new String(buf, lineStart, lineEnd - lineStart);
                        wordIndex++;
                    }
                    lineStart = i + 1;
                }
            }

            if (wordIndex != 2048) {
                throw new RuntimeException(
                    "Expected 2048 words in BIP39 wordlist, got " + wordIndex);
            }

            words = result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read BIP39 wordlist: " + e.getMessage());
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                // ignore close error
            }
        }
    }

    /**
     * Get the word at the given index (0-2047).
     *
     * @param index word index
     * @return the word
     * @throws ArrayIndexOutOfBoundsException if index is out of range
     */
    public static String getWord(int index) {
        if (words == null) {
            init();
        }
        return words[index];
    }

    /**
     * Find the index of a word using binary search.
     * The BIP39 English wordlist is sorted alphabetically.
     *
     * @param word the word to find
     * @return index 0-2047, or -1 if not found
     */
    public static int indexOf(String word) {
        if (words == null) {
            init();
        }
        int low = 0;
        int high = words.length - 1;
        while (low <= high) {
            int mid = (low + high) / 2;
            int cmp = words[mid].compareTo(word);
            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /**
     * Check if the wordlist has been loaded.
     *
     * @return true if init() has completed successfully
     */
    public static boolean isLoaded() {
        return words != null;
    }
}
