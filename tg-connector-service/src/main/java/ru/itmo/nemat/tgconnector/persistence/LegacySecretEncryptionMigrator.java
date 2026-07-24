package ru.itmo.nemat.tgconnector.persistence;

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
        encryptGroupSecrets();
        encryptRegistrationSecrets();
    }

    private void encryptGroupSecrets() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, vk_token, vk_secret, vk_confirmation_code
                FROM curator_vk_groups
                """);
        for (Map<String, Object> row : rows) {
            String token = stringValue(row.get("vk_token"));
            String secret = stringValue(row.get("vk_secret"));
            String confirmation = stringValue(row.get("vk_confirmation_code"));
            if (allEncrypted(token, secret, confirmation)) {
                continue;
            }
            jdbcTemplate.update("""
                            UPDATE curator_vk_groups
                            SET vk_token = ?, vk_secret = ?, vk_confirmation_code = ?
                            WHERE id = ?
                            """,
                    secretCipher.encrypt(token),
                    secretCipher.encrypt(secret),
                    secretCipher.encrypt(confirmation),
                    row.get("id")
            );
        }
    }

    private void encryptRegistrationSecrets() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT tg_chat_id, vk_token, vk_secret, vk_confirmation_code
                FROM registration_contexts
                WHERE vk_token IS NOT NULL
                   OR vk_secret IS NOT NULL
                   OR vk_confirmation_code IS NOT NULL
                """);
        for (Map<String, Object> row : rows) {
            String token = stringValue(row.get("vk_token"));
            String secret = stringValue(row.get("vk_secret"));
            String confirmation = stringValue(row.get("vk_confirmation_code"));
            if (allEncrypted(token, secret, confirmation)) {
                continue;
            }
            jdbcTemplate.update("""
                            UPDATE registration_contexts
                            SET vk_token = ?, vk_secret = ?, vk_confirmation_code = ?
                            WHERE tg_chat_id = ?
                            """,
                    secretCipher.encrypt(token),
                    secretCipher.encrypt(secret),
                    secretCipher.encrypt(confirmation),
                    row.get("tg_chat_id")
            );
        }
    }

    private boolean allEncrypted(String... values) {
        for (String value : values) {
            if (value != null && !secretCipher.isEncrypted(value)) {
                return false;
            }
        }
        return true;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
