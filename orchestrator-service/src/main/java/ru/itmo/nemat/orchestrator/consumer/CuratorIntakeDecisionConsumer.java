package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.CuratorIntakeDecisionEvent;
import ru.itmo.nemat.orchestrator.services.WorkflowOrchestrator;

@Component
@Slf4j
@RequiredArgsConstructor
public class CuratorIntakeDecisionConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;

    @KafkaListener(topics = "curator-intake-decisions", groupId = "orchestrator-group")
    public void consume(String messageJson) {
        try {
            CuratorIntakeDecisionEvent event =
                    objectMapper.readValue(messageJson, CuratorIntakeDecisionEvent.class);
            workflowOrchestrator.handleCuratorIntakeDecision(event);
        } catch (Exception exception) {
            log.error("Failed to process curator intake decision", exception);
            throw new IllegalStateException(
                    "Failed to process curator intake decision",
                    exception
            );
        }
    }
}
