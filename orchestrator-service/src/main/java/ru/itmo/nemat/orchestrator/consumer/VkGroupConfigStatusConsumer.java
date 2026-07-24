package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.VkGroupConfigStatusEvent;
import ru.itmo.nemat.orchestrator.services.VkGroupPromptService;

@Component
@Slf4j
@RequiredArgsConstructor
public class VkGroupConfigStatusConsumer {

    private final ObjectMapper objectMapper;
    private final VkGroupPromptService promptService;

    @KafkaListener(topics = "vk-group-config-status", groupId = "orchestrator-config-status-group")
    public void consume(String payload) {
        try {
            VkGroupConfigStatusEvent event =
                    objectMapper.readValue(payload, VkGroupConfigStatusEvent.class);
            if ("REMOVED".equals(event.status())) {
                promptService.removeAfterSuccessfulDisconnect(
                        event.vkGroupId(),
                        event.configVersion()
                );
            }
        } catch (Exception exception) {
            log.error("Failed to process VK group status", exception);
            throw new IllegalStateException("Failed to process VK group status", exception);
        }
    }
}
