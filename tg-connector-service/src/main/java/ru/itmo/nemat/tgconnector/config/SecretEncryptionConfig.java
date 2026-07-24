package ru.itmo.nemat.tgconnector.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.itmo.nemat.shared.security.SecretCipher;

@Configuration
public class SecretEncryptionConfig {

    @Bean
    public SecretCipher secretCipher(
            @Value("${app.security.encryption-key}") String encryptionKey
    ) {
        return new SecretCipher(encryptionKey);
    }
}
