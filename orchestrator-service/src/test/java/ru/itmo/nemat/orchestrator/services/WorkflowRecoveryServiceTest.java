package ru.itmo.nemat.orchestrator.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.orchestrator.dto.AiGenerationCommand;
import ru.itmo.nemat.orchestrator.dto.BillingChargeCommand;
import ru.itmo.nemat.orchestrator.dto.CuratorApprovalRequest;
import ru.itmo.nemat.orchestrator.dto.CuratorIntakeRequest;
import ru.itmo.nemat.orchestrator.dto.CuratorSystemNotificationCommand;
import ru.itmo.nemat.orchestrator.dto.SendVkMessageCommand;
import ru.itmo.nemat.orchestrator.model.WorkflowState;
import ru.itmo.nemat.orchestrator.model.WorkflowStatus;
import ru.itmo.nemat.orchestrator.producer.AiCommandProducer;
import ru.itmo.nemat.orchestrator.producer.BalanceReservationCommandProducer;
import ru.itmo.nemat.orchestrator.producer.BillingCommandProducer;
import ru.itmo.nemat.orchestrator.producer.BillingRefundCommandProducer;
import ru.itmo.nemat.orchestrator.producer.CuratorRequestProducer;
import ru.itmo.nemat.orchestrator.producer.CuratorIntakeRequestProducer;
import ru.itmo.nemat.orchestrator.producer.CuratorSystemNotificationProducer;
import ru.itmo.nemat.orchestrator.producer.VkMessageProducer;
import ru.itmo.nemat.orchestrator.repository.VkGroupPromptRepository;
import ru.itmo.nemat.orchestrator.repository.WorkflowStateRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRecoveryServiceTest {

    @Mock
    private WorkflowStateRepository workflowRepository;
    @Mock
    private VkGroupPromptRepository promptRepository;
    @Mock
    private BalanceReservationCommandProducer reservationProducer;
    @Mock
    private AiCommandProducer aiProducer;
    @Mock
    private CuratorIntakeRequestProducer curatorIntakeRequestProducer;
    @Mock
    private CuratorRequestProducer curatorRequestProducer;
    @Mock
    private BillingCommandProducer billingProducer;
    @Mock
    private VkMessageProducer vkMessageProducer;
    @Mock
    private BillingRefundCommandProducer refundProducer;
    @Mock
    private CuratorSystemNotificationProducer notificationProducer;

    @InjectMocks
    private WorkflowRecoveryService recoveryService;

    @Test
    void requeuesInitialCuratorQuestion() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = state(
                requestId,
                WorkflowStatus.AWAITING_CURATOR_ACTION
        );
        when(workflowRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        boolean recovered = recoveryService.recover(
                requestId,
                WorkflowStatus.AWAITING_CURATOR_ACTION,
                Instant.now().minusSeconds(60),
                10
        );

        assertThat(recovered).isTrue();
        ArgumentCaptor<CuratorIntakeRequest> captor =
                ArgumentCaptor.forClass(CuratorIntakeRequest.class);
        verify(curatorIntakeRequestProducer).resend(captor.capture());
        assertThat(captor.getValue().requestId()).isEqualTo(requestId);
        assertThat(captor.getValue().studentQuestion()).isEqualTo("Question");
    }

    @Test
    void requeuesAiGenerationWithOriginalRequestId() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = state(requestId, WorkflowStatus.AI_PROCESSING);
        when(workflowRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        boolean recovered = recoveryService.recover(
                requestId,
                WorkflowStatus.AI_PROCESSING,
                Instant.now().minusSeconds(60),
                10
        );

        assertThat(recovered).isTrue();
        assertThat(state.getRecoveryAttempts()).isEqualTo(1);
        ArgumentCaptor<AiGenerationCommand> captor =
                ArgumentCaptor.forClass(AiGenerationCommand.class);
        verify(aiProducer).resendCommand(captor.capture());
        assertThat(captor.getValue().requestId()).isEqualTo(requestId);
        assertThat(captor.getValue().vkChatId()).isEqualTo("200");
    }

    @Test
    void requeuesApprovalWithoutCreatingNewAiGeneration() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = state(requestId, WorkflowStatus.AWAITING_APPROVAL);
        when(workflowRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        boolean recovered = recoveryService.recover(
                requestId,
                WorkflowStatus.AWAITING_APPROVAL,
                Instant.now().minusSeconds(60),
                10
        );

        assertThat(recovered).isTrue();
        ArgumentCaptor<CuratorApprovalRequest> captor =
                ArgumentCaptor.forClass(CuratorApprovalRequest.class);
        verify(curatorRequestProducer).resendApprovalRequest(captor.capture());
        assertThat(captor.getValue().aiSuggestedAnswer()).isEqualTo("Answer");
        verify(aiProducer, never()).resendCommand(any());
    }

    @Test
    void requeuesBillingWithOriginalPriceSnapshot() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = state(requestId, WorkflowStatus.BILLING_PENDING);
        when(workflowRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        boolean recovered = recoveryService.recover(
                requestId,
                WorkflowStatus.BILLING_PENDING,
                Instant.now().minusSeconds(60),
                10
        );

        assertThat(recovered).isTrue();
        ArgumentCaptor<BillingChargeCommand> captor =
                ArgumentCaptor.forClass(BillingChargeCommand.class);
        verify(billingProducer).resendCharge(captor.capture());
        assertThat(captor.getValue().creditsToCharge()).isEqualByComparingTo("1000");
        assertThat(captor.getValue().creditsPerUsd()).isEqualByComparingTo("200000");
    }

    @Test
    void requeuesVkDeliveryWithSameRequestId() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = state(requestId, WorkflowStatus.SENDING_TO_STUDENT);
        when(workflowRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        boolean recovered = recoveryService.recover(
                requestId,
                WorkflowStatus.SENDING_TO_STUDENT,
                Instant.now().minusSeconds(60),
                10
        );

        assertThat(recovered).isTrue();
        ArgumentCaptor<SendVkMessageCommand> captor =
                ArgumentCaptor.forClass(SendVkMessageCommand.class);
        verify(vkMessageProducer).resendCommand(captor.capture());
        assertThat(captor.getValue().requestId()).isEqualTo(requestId);
        assertThat(captor.getValue().text()).isEqualTo("Answer");
    }

    @Test
    void ignoresWorkflowThatAlreadyAdvanced() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = state(requestId, WorkflowStatus.COMPLETED);
        when(workflowRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        boolean recovered = recoveryService.recover(
                requestId,
                WorkflowStatus.SENDING_TO_STUDENT,
                Instant.now().minusSeconds(60),
                10
        );

        assertThat(recovered).isFalse();
        verify(vkMessageProducer, never()).resendCommand(any());
    }

    @Test
    void notifiesOnceWhenRecoveryAttemptsAreExhausted() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = state(requestId, WorkflowStatus.BILLING_PENDING);
        state.markRecoveryAttempt(Instant.now());
        for (int index = 1; index < 10; index++) {
            state.markRecoveryAttempt(Instant.now());
        }
        when(workflowRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        boolean notified = recoveryService.notifyRecoveryExhausted(
                requestId,
                WorkflowStatus.BILLING_PENDING,
                10
        );

        assertThat(notified).isTrue();
        assertThat(state.getRecoveryExhaustedNotifiedAt()).isNotNull();
        ArgumentCaptor<CuratorSystemNotificationCommand> captor =
                ArgumentCaptor.forClass(CuratorSystemNotificationCommand.class);
        verify(notificationProducer).send(captor.capture());
        assertThat(captor.getValue().type())
                .isEqualTo("RECOVERY_EXHAUSTED_BILLING_PENDING");
    }

    private WorkflowState state(UUID requestId, WorkflowStatus status) {
        return WorkflowState.builder()
                .requestId(requestId)
                .vkChatId("200")
                .vkUserId("300")
                .vkGroupId("100")
                .studentQuestion("Question")
                .aiSuggestedAnswer("Answer")
                .tokensUsed(25)
                .providerCostUsd(new BigDecimal("0.005"))
                .creditsToCharge(new BigDecimal("1000"))
                .creditsPerUsd(new BigDecimal("200000"))
                .minimumCharge(new BigDecimal("100"))
                .reservedCredits(new BigDecimal("1000"))
                .reservationExpiresAt(Instant.now().plusSeconds(3600))
                .status(status)
                .statusChangedAt(Instant.now().minusSeconds(600))
                .photoUrls(List.of())
                .build();
    }
}
