package ru.itmo.nemat.orchestrator.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.orchestrator.config.WorkflowWatchdogProperties;
import ru.itmo.nemat.orchestrator.model.WorkflowStatus;
import ru.itmo.nemat.orchestrator.repository.WorkflowStateRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowWatchdogTest {

    @Mock
    private WorkflowStateRepository repository;
    @Mock
    private WorkflowRecoveryService recoveryService;

    private WorkflowWatchdog watchdog;
    private WorkflowWatchdogProperties properties;

    @BeforeEach
    void setUp() {
        properties = new WorkflowWatchdogProperties();
        properties.setBatchSize(25);
        properties.setMaxRecoveryAttempts(7);
        properties.setReservationTimeout(Duration.ofSeconds(30));
        properties.setAiTimeout(Duration.ofMinutes(3));
        properties.setApprovalDeliveryTimeout(Duration.ofMinutes(10));
        properties.setBillingTimeout(Duration.ofMinutes(1));
        properties.setVkDeliveryTimeout(Duration.ofMinutes(1));
        properties.setRefundTimeout(Duration.ofMinutes(1));
        watchdog = new WorkflowWatchdog(repository, recoveryService, properties);
    }

    @Test
    void delegatesStuckWorkflowToRecoveryService() {
        UUID requestId = UUID.randomUUID();
        when(repository.findIdsForWatchdog(
                any(String.class),
                any(Instant.class),
                eq(7),
                eq(25)
        )).thenAnswer(invocation ->
                WorkflowStatus.BILLING_PENDING.name().equals(invocation.getArgument(0))
                        ? List.of(requestId)
                        : List.of()
        );

        watchdog.recoverStuckWorkflows();

        verify(recoveryService).recover(
                eq(requestId),
                eq(WorkflowStatus.BILLING_PENDING),
                any(Instant.class),
                eq(7)
        );
    }

    @Test
    void delegatesExhaustedWorkflowToNotification() {
        UUID requestId = UUID.randomUUID();
        when(repository.findIdsWithExhaustedRecovery(
                any(String.class),
                eq(7),
                eq(25)
        )).thenAnswer(invocation ->
                WorkflowStatus.REFUND_PENDING.name().equals(invocation.getArgument(0))
                        ? List.of(requestId)
                        : List.of()
        );

        watchdog.recoverStuckWorkflows();

        verify(recoveryService).notifyRecoveryExhausted(
                requestId,
                WorkflowStatus.REFUND_PENDING,
                7
        );
    }
}
