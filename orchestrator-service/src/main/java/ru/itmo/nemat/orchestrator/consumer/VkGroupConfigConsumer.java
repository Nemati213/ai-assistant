package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.VkGroupConfigEvent;
import ru.itmo.nemat.orchestrator.services.VkGroupPromptService;

@Component
@Slf4j
@RequiredArgsConstructor
public class VkGroupConfigConsumer {

    private final ObjectMapper objectMapper;
    private final VkGroupPromptService promptService;

    @KafkaListener(topics = "vk-group-configs", groupId = "orchestrator-config-group")
    public void consume(String payload) {
        try {
            VkGroupConfigEvent event = objectMapper.readValue(payload, VkGroupConfigEvent.class);
            promptService.apply(event);
            log.info("System prompt configuration applied for group: {}", event.vkGroupId());
        } catch (Exception e) {
            log.error("Failed to process group config for prompt update", e);
            throw new IllegalStateException("Failed to process group config for prompt update", e);
        }
    }
}
