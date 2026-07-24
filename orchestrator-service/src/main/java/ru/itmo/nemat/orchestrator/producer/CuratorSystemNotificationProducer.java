package ru.itmo.nemat.orchestrator.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.CuratorSystemNotificationCommand;
import ru.itmo.nemat.orchestrator.services.OutboxService;

@Component
@Slf4j
@RequiredArgsConstructor
public class CuratorSystemNotificationProducer {

    private static final String TOPIC = "curator-system-notifications";

    private final OutboxService outboxService;

    public void send(CuratorSystemNotificationCommand command) {
        enqueue(
                command,
                command.requestId() + ":CURATOR_NOTIFICATION:" + command.type()
        );
    }

    public void sendManualDeliveryNotification(
            CuratorSystemNotificationCommand command,
            int deliveryAttempt
    ) {
        enqueue(
                command,
                command.requestId()
                        + ":CURATOR_NOTIFICATION:"
                        + command.type()
                        + ":"
                        + deliveryAttempt
        );
    }

    private void enqueue(
            CuratorSystemNotificationCommand command,
            String deduplicationKey
    ) {
        outboxService.enqueue(
                command.requestId(),
                deduplicationKey,
                TOPIC,
                command.requestId().toString(),
                command
        );
        log.debug(
                "[{}] Curator system notification {} stored in outbox",
                command.requestId(),
                command.type()
        );
    }
}
