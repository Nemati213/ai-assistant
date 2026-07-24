package ru.itmo.nemat.tgconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.bot.CuratorTelegramBot;
import ru.itmo.nemat.tgconnector.dto.VkGroupConfigStatusEvent;
import ru.itmo.nemat.tgconnector.service.VkGroupManagementService;

@Component
@Slf4j
@RequiredArgsConstructor
public class VkGroupConfigStatusConsumer {

    private final ObjectMapper objectMapper;
    private final VkGroupManagementService groupManagementService;
    private final CuratorTelegramBot telegramBot;

    @KafkaListener(topics = "vk-group-config-status", groupId = "tg-config-status-group")
    public void consume(String payload) {
        try {
            VkGroupConfigStatusEvent event =
                    objectMapper.readValue(payload, VkGroupConfigStatusEvent.class);
            groupManagementService.applyStatus(event)
                    .ifPresent(telegramBot::notifyGroupStatus);
        } catch (Exception exception) {
            log.error("Failed to process VK group configuration status", exception);
            throw new IllegalStateException("Failed to process VK group configuration status", exception);
        }
    }
}
