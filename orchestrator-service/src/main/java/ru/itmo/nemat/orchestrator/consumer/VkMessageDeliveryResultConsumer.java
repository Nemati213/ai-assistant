package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.VkMessageDeliveryResultEvent;
import ru.itmo.nemat.orchestrator.services.WorkflowOrchestrator;

@Component
@Slf4j
@RequiredArgsConstructor
public class VkMessageDeliveryResultConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;

    @KafkaListener(topics = "vk-message-delivery-results", groupId = "orchestrator-group")
    public void consume(String payload) {
        try {
            VkMessageDeliveryResultEvent event =
                    objectMapper.readValue(payload, VkMessageDeliveryResultEvent.class);
            workflowOrchestrator.handleVkDeliveryResult(event);
        } catch (Exception exception) {
            log.error("Failed to process VK message delivery result", exception);
            throw new IllegalStateException("Failed to process VK message delivery result", exception);
        }
    }
}
