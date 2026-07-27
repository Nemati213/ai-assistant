package ru.itmo.nemat.tgconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.bot.CuratorTelegramBot;
import ru.itmo.nemat.tgconnector.dto.VkMessageDeliveryResultEvent;
import ru.itmo.nemat.tgconnector.service.BroadcastService;

@Component
@Slf4j
@RequiredArgsConstructor
public class BroadcastDeliveryResultConsumer {

    private final ObjectMapper objectMapper;
    private final BroadcastService broadcastService;
    private final CuratorTelegramBot telegramBot;

    @KafkaListener(
            topics = "vk-message-delivery-results",
            groupId = "tg-broadcast-delivery"
    )
    public void consume(String payload) {
        try {
            VkMessageDeliveryResultEvent event = objectMapper.readValue(
                    payload,
                    VkMessageDeliveryResultEvent.class
            );
            broadcastService.recordDelivery(event)
                    .ifPresent(telegramBot::notifyBroadcastCompleted);
        } catch (Exception exception) {
            log.error("Failed to process broadcast delivery result", exception);
            throw new IllegalStateException(
                    "Failed to process broadcast delivery result",
                    exception
            );
        }
    }
}
