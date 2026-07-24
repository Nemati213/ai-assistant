package ru.itmo.nemat.orchestrator.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.BalanceReleaseCommand;
import ru.itmo.nemat.orchestrator.services.OutboxService;

@Component
@Slf4j
@RequiredArgsConstructor
public class BalanceReleaseCommandProducer {

    private static final String TOPIC = "balance-release-commands";

    private final OutboxService outboxService;

    public void send(BalanceReleaseCommand command) {
        outboxService.enqueue(
                command.requestId(),
                command.requestId() + ":BALANCE_RELEASE",
                TOPIC,
                command.requestId().toString(),
                command
        );
        log.debug("[{}] Balance release stored in outbox", command.requestId());
    }
}
