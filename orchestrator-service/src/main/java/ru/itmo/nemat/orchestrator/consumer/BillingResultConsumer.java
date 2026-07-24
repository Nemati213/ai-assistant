package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.BillingResultEvent;
import ru.itmo.nemat.orchestrator.services.WorkflowOrchestrator;

@Component
@Slf4j
@RequiredArgsConstructor
public class BillingResultConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;

    @KafkaListener(topics = "billing-results", groupId = "orchestrator-group")
    public void consume(String payload) {
        try {
            BillingResultEvent event = objectMapper.readValue(payload, BillingResultEvent.class);
            workflowOrchestrator.handleBillingResult(event);
        } catch (Exception exception) {
            log.error("Failed to process billing result", exception);
            throw new IllegalStateException("Failed to process billing result", exception);
        }
    }
}
