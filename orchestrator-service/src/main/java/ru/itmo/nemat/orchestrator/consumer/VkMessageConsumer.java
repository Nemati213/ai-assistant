package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.VkMessageEvent;
import ru.itmo.nemat.orchestrator.services.WorkflowOrchestrator;

@Component
@Slf4j
@RequiredArgsConstructor
public class VkMessageConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;

    @KafkaListener(topics = "vk-incoming-messages", groupId = "orchestrator-group")
    public void consume(String messageJson) {
        log.debug("VK message received from Kafka topic vk-incoming-messages");

        try {
            VkMessageEvent event = objectMapper.readValue(messageJson, VkMessageEvent.class);

            log.info("[{}] VK event accepted, starting orchestration", event.requestId());

            workflowOrchestrator.startWorkflow(event);
        } catch (Exception exception) {
            log.error("Failed to read VK message from topic vk-incoming-messages", exception);
            throw new IllegalStateException("Failed to process incoming VK message", exception);
        }
    }
}
