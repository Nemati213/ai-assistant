package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.BalanceReservationResultEvent;
import ru.itmo.nemat.orchestrator.services.WorkflowOrchestrator;

@Component
@Slf4j
@RequiredArgsConstructor
public class BalanceReservationResultConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;

    @KafkaListener(topics = "balance-reservation-results", groupId = "orchestrator-group")
    public void consume(String payload) {
        try {
            BalanceReservationResultEvent event =
                    objectMapper.readValue(payload, BalanceReservationResultEvent.class);
            workflowOrchestrator.handleBalanceReservationResult(event);
        } catch (Exception exception) {
            log.error("Failed to process balance reservation result", exception);
            throw new IllegalStateException(
                    "Failed to process balance reservation result",
                    exception
            );
        }
    }
}
