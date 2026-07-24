package ru.itmo.nemat.tgconnector.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTelegramPropertiesTest {

    @Test
    void requiresBotCredentialsAndUsesExplicitAllowlist() {
        AdminTelegramProperties properties = new AdminTelegramProperties();
        properties.setBotName("admin_bot");
        properties.setBotToken("secret");
        properties.setAdminIds(Set.of(10L, 20L));

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.isAllowed(10L)).isTrue();
        assertThat(properties.isAllowed(30L)).isFalse();
        assertThat(properties.isAllowed(null)).isFalse();
    }
}
