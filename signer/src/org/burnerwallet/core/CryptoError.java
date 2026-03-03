package org.burnerwallet.core;

/**
 * Exception class for cryptographic operations.
 * Carries an integer error code to identify the failure category.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class CryptoError extends Exception {

    public static final int ERR_INVALID_MNEMONIC = 1;
    public static final int ERR_INVALID_SEED_LENGTH = 2;
    public static final int ERR_DERIVATION_FAILED = 3;
    public static final int ERR_INVALID_KEY = 4;
    public static final int ERR_ENCODING = 5;
    public static final int ERR_CHECKSUM = 6;

    private int errorCode;

    public CryptoError(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
