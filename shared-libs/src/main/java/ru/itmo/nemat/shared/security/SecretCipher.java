package ru.itmo.nemat.shared.security;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public final class SecretCipher {

    private static final String PREFIX = "enc:v1:";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom;

    public SecretCipher(String base64Key) {
        this(base64Key, new SecureRandom());
    }

    SecretCipher(String base64Key, SecureRandom secureRandom) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("Secret encryption key is required");
        }

        byte[] decodedKey;
        try {
            decodedKey = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Secret encryption key must be valid Base64",
                    exception
            );
        }
        if (decodedKey.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Secret encryption key must decode to exactly 32 bytes"
            );
        }

        this.key = new SecretKeySpec(decodedKey, "AES");
        this.secureRandom = secureRandom;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        if (isEncrypted(plaintext)) {
            return plaintext;
        }

        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce)
                    .put(encrypted)
                    .array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new SecretCryptoException("Failed to encrypt secret", exception);
        }
    }

    public String decrypt(String value) {
        if (value == null || !isEncrypted(value)) {
            return value;
        }

        try {
            byte[] payload = Base64.getUrlDecoder().decode(value.substring(PREFIX.length()));
            if (payload.length <= NONCE_BYTES) {
                throw new SecretCryptoException("Encrypted secret payload is invalid");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] encrypted = new byte[payload.length - NONCE_BYTES];
            System.arraycopy(payload, 0, nonce, 0, nonce.length);
            System.arraycopy(payload, nonce.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new SecretCryptoException(
                    "Encrypted secret cannot be authenticated; check the encryption key",
                    exception
            );
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new SecretCryptoException("Failed to decrypt secret", exception);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }
}
