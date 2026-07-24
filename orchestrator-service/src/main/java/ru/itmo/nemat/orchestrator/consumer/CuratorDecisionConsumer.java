package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.CuratorDecisionEvent;
import ru.itmo.nemat.orchestrator.services.WorkflowOrchestrator;

@Component
@Slf4j
@RequiredArgsConstructor
public class CuratorDecisionConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;

    @KafkaListener(topics = "curator-decisions", groupId = "orchestrator-group")
    public void consume(String messageJson) {
        try {
            CuratorDecisionEvent event = objectMapper.readValue(messageJson, CuratorDecisionEvent.class);
            workflowOrchestrator.handleCuratorDecision(event);
        } catch (Exception e) {
            log.error("Failed to process curator decision", e);
            throw new IllegalStateException("Failed to process curator decision", e);
        }
    }
}
