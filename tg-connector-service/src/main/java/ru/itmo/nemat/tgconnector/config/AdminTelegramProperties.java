package ru.itmo.nemat.tgconnector.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "telegram.admin")
public class AdminTelegramProperties {

    private String botName = "";
    private String botToken = "";
    private Set<Long> adminIds = new LinkedHashSet<>();

    public boolean isConfigured() {
        return botName != null
                && !botName.isBlank()
                && botToken != null
                && !botToken.isBlank();
    }

    public boolean isAllowed(Long telegramUserId) {
        return telegramUserId != null && adminIds.contains(telegramUserId);
    }
}
