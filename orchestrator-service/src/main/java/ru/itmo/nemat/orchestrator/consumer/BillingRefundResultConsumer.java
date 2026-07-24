package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.BillingRefundResultEvent;
import ru.itmo.nemat.orchestrator.services.WorkflowOrchestrator;

@Component
@Slf4j
@RequiredArgsConstructor
public class BillingRefundResultConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;

    @KafkaListener(topics = "billing-refund-results", groupId = "orchestrator-group")
    public void consume(String payload) {
        try {
            BillingRefundResultEvent event =
                    objectMapper.readValue(payload, BillingRefundResultEvent.class);
            workflowOrchestrator.handleBillingRefundResult(event);
        } catch (Exception exception) {
            log.error("Failed to process billing refund result", exception);
            throw new IllegalStateException("Failed to process billing refund result", exception);
        }
    }
}
