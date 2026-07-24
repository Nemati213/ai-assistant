package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.AiGenerationFailedEvent;
import ru.itmo.nemat.orchestrator.services.WorkflowOrchestrator;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiFailureConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;

    @KafkaListener(topics = "ai-generation-failures", groupId = "orchestrator-group")
    public void consume(String payload) {
        try {
            AiGenerationFailedEvent event =
                    objectMapper.readValue(payload, AiGenerationFailedEvent.class);
            workflowOrchestrator.handleAiFailure(event);
        } catch (Exception exception) {
            log.error("Failed to process AI generation failure: {}", payload, exception);
            throw new IllegalStateException(
                    "Failed to process AI generation failure",
                    exception
            );
        }
    }
}
