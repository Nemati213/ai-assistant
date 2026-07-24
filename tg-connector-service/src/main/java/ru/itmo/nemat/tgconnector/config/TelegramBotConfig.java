package ru.itmo.nemat.tgconnector.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.itmo.nemat.tgconnector.bot.AdminTelegramBot;
import ru.itmo.nemat.tgconnector.bot.CuratorTelegramBot;
import ru.itmo.nemat.tgconnector.service.AdminCuratorService;
import ru.itmo.nemat.tgconnector.service.TelegramRateLimiter;

@Configuration
@Slf4j
public class TelegramBotConfig {

    @Bean
    @Conditional(AdminBotConfiguredCondition.class)
    public AdminTelegramBot adminTelegramBot(
            AdminTelegramProperties properties,
            AdminCuratorService adminCuratorService,
            TelegramRateLimiter rateLimiter
    ) {
        return new AdminTelegramBot(properties, adminCuratorService, rateLimiter);
    }

    @Bean
    public TelegramBotsApi telegramBotsApi(
            CuratorTelegramBot curatorBot,
            ObjectProvider<AdminTelegramBot> adminBotProvider,
            AdminTelegramProperties adminProperties,
            @Value("${telegram.bot-token}") String curatorBotToken
    ) throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(curatorBot);

        AdminTelegramBot adminBot = adminBotProvider.getIfAvailable();
        if (adminBot != null) {
            if (adminProperties.getBotToken().equals(curatorBotToken)) {
                throw new IllegalStateException(
                        "Admin bot and curator bot must use different tokens"
                );
            }
            api.registerBot(adminBot);
            if (adminProperties.getAdminIds().isEmpty()) {
                log.warn("Admin bot registered without allowed Telegram user IDs");
            } else {
                log.info(
                        "Admin bot registered for {} allowed Telegram users",
                        adminProperties.getAdminIds().size()
                );
            }
        } else {
            log.info("Admin bot is not configured and will not be registered");
        }
        return api;
    }

    static class AdminBotConfiguredCondition implements Condition {

        @Override
        public boolean matches(
                ConditionContext context,
                AnnotatedTypeMetadata metadata
        ) {
            return StringUtils.hasText(
                    context.getEnvironment().getProperty(
                            "telegram.admin.bot-name"
                    )
            ) && StringUtils.hasText(
                    context.getEnvironment().getProperty(
                            "telegram.admin.bot-token"
                    )
            );
        }
    }
}
