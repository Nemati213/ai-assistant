package ru.itmo.nemat.orchestrator.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.CuratorIntakeRequest;
import ru.itmo.nemat.orchestrator.services.OutboxService;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class CuratorIntakeRequestProducer {

    private static final String TOPIC = "curator-intake-requests";

    private final OutboxService outboxService;

    public void send(CuratorIntakeRequest request) {
        enqueue(request, request.requestId() + ":CURATOR_INTAKE");
    }

    public void resend(CuratorIntakeRequest request) {
        enqueue(
                request,
                request.requestId() + ":CURATOR_INTAKE:RECOVERY:" + UUID.randomUUID()
        );
    }

    private void enqueue(CuratorIntakeRequest request, String deduplicationKey) {
        outboxService.enqueue(
                request.requestId(),
                deduplicationKey,
                TOPIC,
                request.requestId().toString(),
                request
        );
        log.debug("[{}] Curator intake request stored in outbox", request.requestId());
    }
}
