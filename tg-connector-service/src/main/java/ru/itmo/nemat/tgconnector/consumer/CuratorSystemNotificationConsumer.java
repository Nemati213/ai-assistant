package ru.itmo.nemat.tgconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.bot.CuratorTelegramBot;
import ru.itmo.nemat.tgconnector.dto.CuratorSystemNotificationCommand;
import ru.itmo.nemat.tgconnector.service.CuratorRoutingService;

@Component
@ConditionalOnProperty(
        name = "curator.workflow.mode",
        havingValue = "telegram",
        matchIfMissing = true
)
@Slf4j
@RequiredArgsConstructor
public class CuratorSystemNotificationConsumer {

    private final ObjectMapper objectMapper;
    private final CuratorRoutingService routingService;
    private final CuratorTelegramBot telegramBot;

    @KafkaListener(topics = "curator-system-notifications", groupId = "tg-notification-group")
    public void consume(String payload) {
        try {
            CuratorSystemNotificationCommand command = objectMapper.readValue(
                    payload,
                    CuratorSystemNotificationCommand.class
            );
            CuratorRoutingService.CuratorRoute route =
                    routingService.resolveRegistered(command.vkGroupId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "No curator route for VK group " + command.vkGroupId()
                            ));
            telegramBot.sendSystemNotification(route.tgChatId(), command);
        } catch (Exception exception) {
            log.error("Failed to deliver curator system notification", exception);
            throw new IllegalStateException(
                    "Failed to deliver curator system notification",
                    exception
            );
        }
    }
}
