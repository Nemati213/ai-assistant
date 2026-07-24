package ru.itmo.nemat.orchestrator.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.CuratorApprovalRequest;
import ru.itmo.nemat.orchestrator.services.OutboxService;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class CuratorRequestProducer {

    private static final String TOPIC = "curator-approval-requests";

    private final OutboxService outboxService;

    public void sendApprovalRequest(CuratorApprovalRequest request) {
        enqueue(request, request.requestId() + ":CURATOR_APPROVAL");
    }

    public void resendApprovalRequest(CuratorApprovalRequest request) {
        enqueue(
                request,
                request.requestId() + ":CURATOR_APPROVAL:RECOVERY:" + UUID.randomUUID()
        );
    }

    private void enqueue(CuratorApprovalRequest request, String deduplicationKey) {
        outboxService.enqueue(
                request.requestId(),
                deduplicationKey,
                TOPIC,
                request.requestId().toString(),
                request
        );
        log.debug("[{}] Curator approval request stored in outbox", request.requestId());
    }
}
