package ru.itmo.nemat.orchestrator.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.BillingRefundCommand;
import ru.itmo.nemat.orchestrator.services.OutboxService;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class BillingRefundCommandProducer {

    private static final String TOPIC = "billing-refund-commands";

    private final OutboxService outboxService;

    public void sendRefund(BillingRefundCommand command) {
        enqueue(command, command.requestId() + ":BILLING_REFUND");
    }

    public void resendRefund(BillingRefundCommand command) {
        enqueue(
                command,
                command.requestId() + ":BILLING_REFUND:RECOVERY:" + UUID.randomUUID()
        );
    }

    private void enqueue(BillingRefundCommand command, String deduplicationKey) {
        outboxService.enqueue(
                command.requestId(),
                deduplicationKey,
                TOPIC,
                command.requestId().toString(),
                command
        );
        log.debug("[{}] Billing refund command stored in outbox", command.requestId());
    }
}
