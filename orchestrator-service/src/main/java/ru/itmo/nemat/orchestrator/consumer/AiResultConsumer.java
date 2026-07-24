package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.AiAnswerGeneratedEvent;
import ru.itmo.nemat.orchestrator.services.WorkflowOrchestrator;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiResultConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;

    @KafkaListener(topics = "ai-generation-results", groupId = "orchestrator-group")
    public void consume(String messageJson) {
        try {
            AiAnswerGeneratedEvent event =
                    objectMapper.readValue(messageJson, AiAnswerGeneratedEvent.class);
            log.info("[{}] Stored AI result received", event.requestId());
            workflowOrchestrator.handleAiResponse(event);
        } catch (Exception exception) {
            log.error("Failed to process AI generation result", exception);
            throw new IllegalStateException(
                    "Failed to process AI generation result",
                    exception
            );
        }
    }
}
