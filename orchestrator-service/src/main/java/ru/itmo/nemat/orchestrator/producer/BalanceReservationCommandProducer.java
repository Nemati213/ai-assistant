package ru.itmo.nemat.orchestrator.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.BalanceReservationCommand;
import ru.itmo.nemat.orchestrator.services.OutboxService;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class BalanceReservationCommandProducer {

    private static final String TOPIC = "balance-reservation-commands";

    private final OutboxService outboxService;

    public void send(BalanceReservationCommand command) {
        enqueue(command, command.requestId() + ":BALANCE_RESERVATION");
    }

    public void resend(BalanceReservationCommand command) {
        enqueue(
                command,
                command.requestId() + ":BALANCE_RESERVATION:RECOVERY:" + UUID.randomUUID()
        );
    }

    private void enqueue(BalanceReservationCommand command, String deduplicationKey) {
        outboxService.enqueue(
                command.requestId(),
                deduplicationKey,
                TOPIC,
                command.requestId().toString(),
                command
        );
        log.debug("[{}] Balance reservation stored in outbox", command.requestId());
    }
}
