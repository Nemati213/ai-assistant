package ru.itmo.nemat.vkconnector.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.shared.security.SecretCipher;

import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class LegacySecretEncryptionMigrator implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final SecretCipher secretCipher;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT vk_group_id, vk_token, vk_secret, vk_confirmation_code
                FROM vk_group_credentials
                """);
        for (Map<String, Object> row : rows) {
            String token = row.get("vk_token").toString();
            String secret = row.get("vk_secret").toString();
            String confirmation = row.get("vk_confirmation_code").toString();
            if (secretCipher.isEncrypted(token)
                    && secretCipher.isEncrypted(secret)
                    && secretCipher.isEncrypted(confirmation)) {
                continue;
            }
            jdbcTemplate.update("""
                            UPDATE vk_group_credentials
                            SET vk_token = ?, vk_secret = ?, vk_confirmation_code = ?
                            WHERE vk_group_id = ?
                            """,
                    secretCipher.encrypt(token),
                    secretCipher.encrypt(secret),
                    secretCipher.encrypt(confirmation),
                    row.get("vk_group_id")
            );
        }
    }
}
