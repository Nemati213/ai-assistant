package ru.itmo.nemat.tgconnector.persistence;

import org.junit.jupiter.api.Test;
import ru.itmo.nemat.shared.security.SecretCipher;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedStringConverterTest {

    @Test
    void storesCiphertextAndRestoresPlaintext() {
        EncryptedStringConverter converter = new EncryptedStringConverter(
                new SecretCipher(Base64.getEncoder().encodeToString(
                        "0123456789abcdef0123456789abcdef"
                                .getBytes(StandardCharsets.UTF_8)
                ))
        );

        String stored = converter.convertToDatabaseColumn("token");

        assertThat(stored).startsWith("enc:v1:");
        assertThat(stored).doesNotContain("token");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("token");
    }
}
