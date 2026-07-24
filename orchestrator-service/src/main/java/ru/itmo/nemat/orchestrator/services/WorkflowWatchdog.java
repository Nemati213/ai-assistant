package ru.itmo.nemat.orchestrator.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.itmo.nemat.orchestrator.config.WorkflowWatchdogProperties;
import ru.itmo.nemat.orchestrator.model.WorkflowStatus;
import ru.itmo.nemat.orchestrator.repository.WorkflowStateRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowWatchdog {

    private final WorkflowStateRepository repository;
    private final WorkflowRecoveryService recoveryService;
    private final WorkflowWatchdogProperties properties;

    @Scheduled(fixedDelayString = "${app.workflow-watchdog.poll-interval-ms:30000}")
    public void recoverStuckWorkflows() {
        recoverAndNotify(
                WorkflowStatus.AWAITING_CURATOR_ACTION,
                properties.getCuratorActionTimeout()
        );
        recoverAndNotify(WorkflowStatus.RESERVATION_PENDING, properties.getReservationTimeout());
        recoverAndNotify(WorkflowStatus.AI_PROCESSING, properties.getAiTimeout());
        recoverAndNotify(WorkflowStatus.AWAITING_APPROVAL, properties.getApprovalDeliveryTimeout());
        recoverAndNotify(WorkflowStatus.BILLING_PENDING, properties.getBillingTimeout());
        recoverAndNotify(WorkflowStatus.SENDING_TO_STUDENT, properties.getVkDeliveryTimeout());
        recoverAndNotify(WorkflowStatus.REFUND_PENDING, properties.getRefundTimeout());
    }

    private void recoverAndNotify(WorkflowStatus status, Duration timeout) {
        Instant cutoff = Instant.now().minus(timeout);
        List<UUID> requestIds = repository.findIdsForWatchdog(
                status.name(),
                cutoff,
                properties.getMaxRecoveryAttempts(),
                properties.getBatchSize()
        );
        for (UUID requestId : requestIds) {
            try {
                recoveryService.recover(
                        requestId,
                        status,
                        cutoff,
                        properties.getMaxRecoveryAttempts()
                );
            } catch (RuntimeException exception) {
                log.error(
                        "[{}] Watchdog failed to recover workflow in status {}",
                        requestId,
                        status,
                        exception
                );
            }
        }

        List<UUID> exhaustedIds = repository.findIdsWithExhaustedRecovery(
                status.name(),
                properties.getMaxRecoveryAttempts(),
                properties.getBatchSize()
        );
        for (UUID requestId : exhaustedIds) {
            try {
                recoveryService.notifyRecoveryExhausted(
                        requestId,
                        status,
                        properties.getMaxRecoveryAttempts()
                );
            } catch (RuntimeException exception) {
                log.error(
                        "[{}] Failed to notify about exhausted recovery in status {}",
                        requestId,
                        status,
                        exception
                );
            }
        }
    }
}
