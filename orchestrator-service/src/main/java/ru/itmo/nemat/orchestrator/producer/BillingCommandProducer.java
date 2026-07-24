package ru.itmo.nemat.orchestrator.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.BillingChargeCommand;
import ru.itmo.nemat.orchestrator.services.OutboxService;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class BillingCommandProducer {

    private static final String TOPIC = "billing-charge-commands";

    private final OutboxService outboxService;

    public void sendCharge(BillingChargeCommand command) {
        enqueue(command, command.requestId() + ":BILLING_CHARGE");
    }

    public void resendCharge(BillingChargeCommand command) {
        enqueue(
                command,
                command.requestId() + ":BILLING_CHARGE:RECOVERY:" + UUID.randomUUID()
        );
    }

    private void enqueue(BillingChargeCommand command, String deduplicationKey) {
        outboxService.enqueue(
                command.requestId(),
                deduplicationKey,
                TOPIC,
                command.requestId().toString(),
                command
        );
        log.debug("[{}] Billing charge command stored in outbox", command.requestId());
    }
}
