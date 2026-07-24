package ru.itmo.nemat.tgconnector.security;

import org.junit.jupiter.api.Test;
import ru.itmo.nemat.shared.security.SecretCipher;
import ru.itmo.nemat.shared.security.SecretCryptoException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
    );

    @Test
    void encryptsAndDecryptsSecret() {
        SecretCipher cipher = new SecretCipher(KEY);

        String encrypted = cipher.encrypt("vk-secret-token");

        assertThat(encrypted).startsWith("enc:v1:");
        assertThat(encrypted).doesNotContain("vk-secret-token");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("vk-secret-token");
    }

    @Test
    void usesDifferentNonceForEveryEncryption() {
        SecretCipher cipher = new SecretCipher(KEY);

        assertThat(cipher.encrypt("same-value"))
                .isNotEqualTo(cipher.encrypt("same-value"));
    }

    @Test
    void keepsLegacyPlaintextReadableDuringMigration() {
        SecretCipher cipher = new SecretCipher(KEY);

        assertThat(cipher.decrypt("legacy-plaintext")).isEqualTo("legacy-plaintext");
    }

    @Test
    void rejectsWrongKey() {
        SecretCipher first = new SecretCipher(KEY);
        SecretCipher second = new SecretCipher(Base64.getEncoder().encodeToString(
                "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8)
        ));

        assertThatThrownBy(() -> second.decrypt(first.encrypt("secret")))
                .isInstanceOf(SecretCryptoException.class)
                .hasMessageContaining("encryption key");
    }
}
