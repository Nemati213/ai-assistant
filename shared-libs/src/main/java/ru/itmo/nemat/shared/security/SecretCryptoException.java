package ru.itmo.nemat.shared.security;

public class SecretCryptoException extends RuntimeException {

    public SecretCryptoException(String message) {
        super(message);
    }

    public SecretCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
