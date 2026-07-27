package ru.itmo.nemat.orchestrator.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.orchestrator.dto.AiAnswerGeneratedEvent;
import ru.itmo.nemat.orchestrator.dto.AiGenerationFailedEvent;
import ru.itmo.nemat.orchestrator.dto.BillingChargeCommand;
import ru.itmo.nemat.orchestrator.dto.BalanceReleaseCommand;
import ru.itmo.nemat.orchestrator.dto.BalanceReservationCommand;
import ru.itmo.nemat.orchestrator.dto.BalanceReservationResultEvent;
import ru.itmo.nemat.orchestrator.dto.BillingRefundCommand;
import ru.itmo.nemat.orchestrator.dto.BillingRefundResultEvent;
import ru.itmo.nemat.orchestrator.dto.BillingResultEvent;
import ru.itmo.nemat.orchestrator.dto.AiGenerationCommand;
import ru.itmo.nemat.orchestrator.dto.CuratorApprovalRequest;
import ru.itmo.nemat.orchestrator.dto.CuratorDecisionEvent;
import ru.itmo.nemat.orchestrator.dto.CuratorIntakeDecisionEvent;
import ru.itmo.nemat.orchestrator.dto.CuratorIntakeRequest;
import ru.itmo.nemat.orchestrator.dto.CuratorSystemNotificationCommand;
import ru.itmo.nemat.orchestrator.dto.SendVkMessageCommand;
import ru.itmo.nemat.orchestrator.dto.StudentConversationMessageEvent;
import ru.itmo.nemat.orchestrator.dto.VkMessageDeliveryResultEvent;
import ru.itmo.nemat.orchestrator.dto.VkMessageEvent;
import ru.itmo.nemat.orchestrator.model.WorkflowState;
import ru.itmo.nemat.orchestrator.model.WorkflowResponseMode;
import ru.itmo.nemat.orchestrator.model.WorkflowStatus;
import ru.itmo.nemat.orchestrator.producer.AiCommandProducer;
import ru.itmo.nemat.orchestrator.producer.BillingCommandProducer;
import ru.itmo.nemat.orchestrator.producer.BalanceReleaseCommandProducer;
import ru.itmo.nemat.orchestrator.producer.BalanceReservationCommandProducer;
import ru.itmo.nemat.orchestrator.producer.BillingRefundCommandProducer;
import ru.itmo.nemat.orchestrator.producer.CuratorRequestProducer;
import ru.itmo.nemat.orchestrator.producer.CuratorIntakeRequestProducer;
import ru.itmo.nemat.orchestrator.producer.CuratorSystemNotificationProducer;
import ru.itmo.nemat.orchestrator.producer.StudentConversationProducer;
import ru.itmo.nemat.orchestrator.producer.VkMessageProducer;
import ru.itmo.nemat.orchestrator.repository.VkGroupPromptRepository;
import ru.itmo.nemat.orchestrator.repository.WorkflowStateRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowOrchestratorTest {

    @Mock
    private WorkflowStateRepository workflowStateRepository;
    @Mock
    private VkGroupPromptRepository promptRepository;
    @Mock
    private AiCommandProducer aiCommandProducer;
    @Mock
    private BalanceReservationCommandProducer balanceReservationCommandProducer;
    @Mock
    private BalanceReleaseCommandProducer balanceReleaseCommandProducer;
    @Mock
    private CuratorIntakeRequestProducer curatorIntakeRequestProducer;
    @Mock
    private CuratorRequestProducer curatorRequestProducer;
    @Mock
    private BillingCommandProducer billingCommandProducer;
    @Mock
    private BillingRefundCommandProducer billingRefundCommandProducer;
    @Mock
    private CuratorSystemNotificationProducer notificationProducer;
    @Mock
    private VkMessageProducer vkMessageProducer;
    @Mock
    private StudentConversationProducer studentConversationProducer;
    @Mock
    private BillingPricingService billingPricingService;

    @InjectMocks
    private WorkflowOrchestrator orchestrator;

    @Test
    void ignoresRepeatedVkEvent() {
        UUID requestId = UUID.randomUUID();
        when(workflowStateRepository.existsById(requestId)).thenReturn(true);

        orchestrator.startWorkflow(vkEvent(requestId));

        verify(workflowStateRepository, never()).saveAndFlush(any());
        verify(aiCommandProducer, never()).sendCommand(any());
    }

    @Test
    void sendsQuestionAndPhotosToCuratorBeforeUsingAi() {
        UUID requestId = UUID.randomUUID();
        VkMessageEvent event = new VkMessageEvent(
                requestId,
                "200",
                "300",
                "Question with photos",
                "100",
                1L,
                List.of("https://vk.test/one.jpg", "https://vk.test/two.jpg")
        );

        orchestrator.startWorkflow(event);

        ArgumentCaptor<CuratorIntakeRequest> intakeCaptor =
                ArgumentCaptor.forClass(CuratorIntakeRequest.class);
        verify(curatorIntakeRequestProducer).send(intakeCaptor.capture());
        assertThat(intakeCaptor.getValue().requestId()).isEqualTo(requestId);
        assertThat(intakeCaptor.getValue().studentQuestion())
                .isEqualTo("Question with photos");
        assertThat(intakeCaptor.getValue().photoUrls()).containsExactly(
                "https://vk.test/one.jpg",
                "https://vk.test/two.jpg"
        );
        verify(balanceReservationCommandProducer, never()).send(any());
        verify(aiCommandProducer, never()).sendCommand(any());
    }

    @Test
    void reservesBalanceOnlyAfterCuratorChoosesAi() {
        UUID requestId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(3600);
        WorkflowState state = workflow(
                requestId,
                WorkflowStatus.AWAITING_CURATOR_ACTION
        );
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));
        when(billingPricingService.minimumCharge()).thenReturn(new BigDecimal("100"));
        when(billingPricingService.reservationCredits()).thenReturn(new BigDecimal("1000"));
        when(billingPricingService.reservationExpiresAt()).thenReturn(expiresAt);

        orchestrator.handleCuratorIntakeDecision(
                new CuratorIntakeDecisionEvent(requestId, "SEND_TO_AI", null, null)
        );

        ArgumentCaptor<BalanceReservationCommand> balanceCaptor =
                ArgumentCaptor.forClass(BalanceReservationCommand.class);
        verify(balanceReservationCommandProducer).send(balanceCaptor.capture());
        assertThat(balanceCaptor.getValue().reservedCredits()).isEqualByComparingTo("1000");
        assertThat(state.getResponseMode()).isEqualTo(WorkflowResponseMode.AI);
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.RESERVATION_PENDING);
        verify(aiCommandProducer, never()).sendCommand(any());
    }

    @Test
    void sendsManualAnswerDirectlyToVkWithoutBilling() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(
                requestId,
                WorkflowStatus.AWAITING_CURATOR_ACTION
        );
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        orchestrator.handleCuratorIntakeDecision(
                new CuratorIntakeDecisionEvent(
                        requestId,
                        "MANUAL_ANSWER",
                        "Manual curator answer",
                        null
                )
        );

        ArgumentCaptor<SendVkMessageCommand> commandCaptor =
                ArgumentCaptor.forClass(SendVkMessageCommand.class);
        verify(vkMessageProducer).sendCommand(commandCaptor.capture());
        assertThat(commandCaptor.getValue().text()).isEqualTo("Manual curator answer");
        assertThat(commandCaptor.getValue().deliveryAttempt()).isEqualTo(1);
        assertThat(state.getAiSuggestedAnswer()).isEqualTo("Manual curator answer");
        assertThat(state.getResponseMode()).isEqualTo(WorkflowResponseMode.MANUAL);
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.SENDING_TO_STUDENT);
        verify(balanceReservationCommandProducer, never()).send(any());
        verify(billingCommandProducer, never()).sendCharge(any());
    }

    @Test
    void forwardsVkPhotoUrlsAfterBalanceIsAvailable() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = WorkflowState.builder()
                .requestId(requestId)
                .vkChatId("200")
                .vkUserId("300")
                .vkGroupId("100")
                .studentQuestion("Question with photos")
                .status(WorkflowStatus.RESERVATION_PENDING)
                .minimumCharge(new BigDecimal("100"))
                .reservedCredits(new BigDecimal("1000"))
                .reservationExpiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .photoUrls(List.of(
                        "https://vk.test/one.jpg",
                        "https://vk.test/two.jpg"
                ))
                .build();
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        orchestrator.handleBalanceReservationResult(new BalanceReservationResultEvent(
                requestId,
                "RESERVED",
                new BigDecimal("5000"),
                new BigDecimal("4000"),
                new BigDecimal("1000"),
                Instant.parse("2030-01-01T00:00:00Z"),
                null
        ));

        ArgumentCaptor<AiGenerationCommand> captor =
                ArgumentCaptor.forClass(AiGenerationCommand.class);
        verify(aiCommandProducer).sendCommand(captor.capture());
        assertThat(captor.getValue().photoUrls()).containsExactly(
                "https://vk.test/one.jpg",
                "https://vk.test/two.jpg"
        );
        assertThat(captor.getValue().vkUserId()).isEqualTo("300");
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.AI_PROCESSING);
        assertThat(state.getAvailableBalanceAfterReservation()).isEqualByComparingTo("4000");
    }

    @Test
    void blocksAiWhenBalanceIsInsufficient() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.RESERVATION_PENDING);
        state.setReservedCredits(new BigDecimal("1000"));
        state.setReservationExpiresAt(Instant.parse("2030-01-01T00:00:00Z"));
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        orchestrator.handleBalanceReservationResult(new BalanceReservationResultEvent(
                requestId,
                "INSUFFICIENT_FUNDS",
                new BigDecimal("50"),
                new BigDecimal("50"),
                new BigDecimal("1000"),
                Instant.parse("2030-01-01T00:00:00Z"),
                "Insufficient available balance for AI generation"
        ));

        verify(aiCommandProducer, never()).sendCommand(any());
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.RESERVATION_BLOCKED);
        assertThat(state.getAvailableBalanceAfterReservation()).isEqualByComparingTo("50");
        assertThat(state.getReservationError())
                .isEqualTo("Insufficient available balance for AI generation");
    }

    @Test
    void ignoresRepeatedAiResultAfterApprovalRequestWasCreated() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.AWAITING_APPROVAL);
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleAiResponse(new AiAnswerGeneratedEvent(
                requestId,
                "200",
                "100",
                "Repeated answer",
                12,
                new BigDecimal("0.001")
        ));

        verify(curatorRequestProducer, never()).sendApprovalRequest(any());
        assertThat(state.getAiSuggestedAnswer()).isNull();
    }

    @Test
    void marksWorkflowFailedAfterAiFailure() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.AI_PROCESSING);
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));
        Instant failedAt = Instant.now();

        orchestrator.handleAiFailure(new AiGenerationFailedEvent(
                requestId,
                "200",
                "100",
                "OpenRouter unavailable",
                failedAt
        ));

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.AI_FAILED);
        assertThat(state.getAiError()).isEqualTo("OpenRouter unavailable");
        assertThat(state.getAiFailedAt()).isEqualTo(failedAt);
        ArgumentCaptor<BalanceReleaseCommand> releaseCaptor =
                ArgumentCaptor.forClass(BalanceReleaseCommand.class);
        verify(balanceReleaseCommandProducer).send(releaseCaptor.capture());
        assertThat(releaseCaptor.getValue().requestId()).isEqualTo(requestId);
        verify(curatorRequestProducer, never()).sendApprovalRequest(any());
        ArgumentCaptor<CuratorSystemNotificationCommand> notificationCaptor =
                ArgumentCaptor.forClass(CuratorSystemNotificationCommand.class);
        verify(notificationProducer).send(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().type()).isEqualTo("AI_FAILED");
    }

    @Test
    void ignoresLateAiFailureAfterSuccessfulGeneration() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.AWAITING_APPROVAL);
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        orchestrator.handleAiFailure(new AiGenerationFailedEvent(
                requestId,
                "200",
                "100",
                "Late timeout",
                Instant.now()
        ));

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.AWAITING_APPROVAL);
        assertThat(state.getAiError()).isNull();
    }

    @Test
    void calculatesCreditsFromProviderCostBeforeApproval() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.AI_PROCESSING);
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));
        when(billingPricingService.creditsPerUsd()).thenReturn(new BigDecimal("200000"));
        when(billingPricingService.minimumCharge()).thenReturn(new BigDecimal("100"));
        when(billingPricingService.calculate(new BigDecimal("0.005")))
                .thenReturn(new BigDecimal("1000"));

        orchestrator.handleAiResponse(new AiAnswerGeneratedEvent(
                requestId,
                "200",
                "100",
                "Answer",
                500,
                new BigDecimal("0.005")
        ));

        ArgumentCaptor<CuratorApprovalRequest> captor =
                ArgumentCaptor.forClass(CuratorApprovalRequest.class);
        verify(curatorRequestProducer).sendApprovalRequest(captor.capture());
        assertThat(captor.getValue().creditsToCharge()).isEqualByComparingTo("1000");
        assertThat(state.getProviderCostUsd()).isEqualByComparingTo("0.005");
        assertThat(state.getCreditsToCharge()).isEqualByComparingTo("1000");
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.AWAITING_APPROVAL);
    }

    @Test
    void ignoresRepeatedCuratorApproval() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.BILLING_PENDING);
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleCuratorDecision(new CuratorDecisionEvent(requestId, "APPROVED", null));

        verify(vkMessageProducer, never()).sendCommand(any());
    }

    @Test
    void requestsBillingOnlyForFirstApproval() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.AWAITING_APPROVAL);
        state.setAiSuggestedAnswer("Answer");
        state.setTokensUsed(25);
        state.setProviderCostUsd(new BigDecimal("0.005"));
        state.setCreditsToCharge(new BigDecimal("1000"));
        state.setCreditsPerUsd(new BigDecimal("200000"));
        state.setMinimumCharge(new BigDecimal("100"));
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleCuratorDecision(new CuratorDecisionEvent(requestId, "APPROVED", null));

        ArgumentCaptor<BillingChargeCommand> commandCaptor =
                ArgumentCaptor.forClass(BillingChargeCommand.class);
        verify(billingCommandProducer).sendCharge(commandCaptor.capture());
        assertThat(commandCaptor.getValue().requestId()).isEqualTo(requestId);
        assertThat(commandCaptor.getValue().aiTokens()).isEqualTo(25);
        assertThat(commandCaptor.getValue().creditsToCharge())
                .isEqualByComparingTo("1000");
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.BILLING_PENDING);
        verify(vkMessageProducer, never()).sendCommand(any());
    }

    @Test
    void usesEditedAnswerForApprovedWorkflow() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.AWAITING_APPROVAL);
        state.setAiSuggestedAnswer("AI answer");
        state.setTokensUsed(25);
        state.setProviderCostUsd(new BigDecimal("0.005"));
        state.setCreditsToCharge(new BigDecimal("1000"));
        state.setCreditsPerUsd(new BigDecimal("200000"));
        state.setMinimumCharge(new BigDecimal("100"));
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        orchestrator.handleCuratorDecision(
                new CuratorDecisionEvent(requestId, "APPROVED", "Edited answer")
        );

        assertThat(state.getAiSuggestedAnswer()).isEqualTo("Edited answer");
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.BILLING_PENDING);
        verify(billingCommandProducer).sendCharge(any());
    }

    @Test
    void rejectionReleasesReservationWithoutBilling() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.AWAITING_APPROVAL);
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        orchestrator.handleCuratorDecision(
                new CuratorDecisionEvent(requestId, "REJECTED", null)
        );

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.REJECTED);
        assertThat(state.getCompletedAt()).isNotNull();
        ArgumentCaptor<BalanceReleaseCommand> captor =
                ArgumentCaptor.forClass(BalanceReleaseCommand.class);
        verify(balanceReleaseCommandProducer).send(captor.capture());
        assertThat(captor.getValue().requestId()).isEqualTo(requestId);
        verify(billingCommandProducer, never()).sendCharge(any());
        verify(vkMessageProducer, never()).sendCommand(any());
    }

    @Test
    void sendsVkCommandAfterSuccessfulBilling() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.BILLING_PENDING);
        state.setAiSuggestedAnswer("Answer");
        state.setTokensUsed(25);
        state.setCreditsToCharge(new BigDecimal("1000"));
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleBillingResult(new BillingResultEvent(
                requestId,
                "CHARGED",
                new BigDecimal("1000"),
                new BigDecimal("975"),
                null
        ));

        ArgumentCaptor<SendVkMessageCommand> commandCaptor =
                ArgumentCaptor.forClass(SendVkMessageCommand.class);
        verify(vkMessageProducer).sendCommand(commandCaptor.capture());
        assertThat(commandCaptor.getValue().requestId()).isEqualTo(requestId);
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.SENDING_TO_STUDENT);
    }

    @Test
    void doesNotSendVkCommandWhenBalanceIsInsufficient() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.BILLING_PENDING);
        state.setTokensUsed(25);
        state.setCreditsToCharge(new BigDecimal("1000"));
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleBillingResult(new BillingResultEvent(
                requestId,
                "INSUFFICIENT_FUNDS",
                BigDecimal.ZERO,
                new BigDecimal("10"),
                "Insufficient token balance"
        ));

        verify(vkMessageProducer, never()).sendCommand(any());
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.BILLING_FAILED);
        assertThat(state.getBillingError()).isEqualTo("Insufficient token balance");
    }

    @Test
    void completesWorkflowAfterSuccessfulVkDelivery() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.SENDING_TO_STUDENT);
        state.setAiSuggestedAnswer("Answer");
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleVkDeliveryResult(
                new VkMessageDeliveryResultEvent(requestId, true, 555L, null, 1)
        );

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(state.getVkMessageId()).isEqualTo(555L);
        assertThat(state.getDeliveryError()).isNull();
        assertThat(state.getCompletedAt()).isNotNull();
        ArgumentCaptor<StudentConversationMessageEvent> historyCaptor =
                ArgumentCaptor.forClass(StudentConversationMessageEvent.class);
        verify(studentConversationProducer)
                .sendDeliveredAnswer(historyCaptor.capture());
        assertThat(historyCaptor.getValue().vkUserId()).isEqualTo("300");
        assertThat(historyCaptor.getValue().text()).isEqualTo("Answer");
    }

    @Test
    void requestsRefundAfterVkApiError() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.SENDING_TO_STUDENT);
        state.setCreditsToCharge(new BigDecimal("1000"));
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleVkDeliveryResult(
                new VkMessageDeliveryResultEvent(requestId, false, null, "VK API error 5", 1)
        );

        ArgumentCaptor<BillingRefundCommand> commandCaptor =
                ArgumentCaptor.forClass(BillingRefundCommand.class);
        verify(billingRefundCommandProducer).sendRefund(commandCaptor.capture());
        assertThat(commandCaptor.getValue().requestId()).isEqualTo(requestId);
        assertThat(commandCaptor.getValue().reason()).isEqualTo("VK API error 5");
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.REFUND_PENDING);
        assertThat(state.getDeliveryError()).isEqualTo("VK API error 5");
        assertThat(state.getCompletedAt()).isNull();
    }

    @Test
    void doesNotRequestRefundAfterManualVkDeliveryError() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.SENDING_TO_STUDENT);
        state.setResponseMode(WorkflowResponseMode.MANUAL);
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleVkDeliveryResult(
                new VkMessageDeliveryResultEvent(requestId, false, null, "VK API error 5", 1)
        );

        verify(billingRefundCommandProducer, never()).sendRefund(any());
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.DELIVERY_FAILED);
        assertThat(state.getDeliveryError()).isEqualTo("VK API error 5");
        ArgumentCaptor<CuratorSystemNotificationCommand> notificationCaptor =
                ArgumentCaptor.forClass(CuratorSystemNotificationCommand.class);
        verify(notificationProducer).sendManualDeliveryNotification(
                notificationCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(1)
        );
        assertThat(notificationCaptor.getValue().type())
                .isEqualTo("MANUAL_DELIVERY_FAILED");
        assertThat(notificationCaptor.getValue().deliveryAttempt()).isEqualTo(1);
    }

    @Test
    void retriesFailedManualDeliveryWithNextAttempt() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.DELIVERY_FAILED);
        state.setResponseMode(WorkflowResponseMode.MANUAL);
        state.setAiSuggestedAnswer("Manual answer");
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        orchestrator.handleCuratorIntakeDecision(new CuratorIntakeDecisionEvent(
                requestId,
                "RETRY_MANUAL_DELIVERY",
                null,
                1
        ));

        ArgumentCaptor<SendVkMessageCommand> commandCaptor =
                ArgumentCaptor.forClass(SendVkMessageCommand.class);
        verify(vkMessageProducer).resendCommand(commandCaptor.capture());
        assertThat(commandCaptor.getValue().deliveryAttempt()).isEqualTo(2);
        assertThat(commandCaptor.getValue().text()).isEqualTo("Manual answer");
        assertThat(state.getDeliveryAttempt()).isEqualTo(2);
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.SENDING_TO_STUDENT);
    }

    @Test
    void cancelsFailedManualDelivery() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.DELIVERY_FAILED);
        state.setResponseMode(WorkflowResponseMode.MANUAL);
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        orchestrator.handleCuratorIntakeDecision(new CuratorIntakeDecisionEvent(
                requestId,
                "CANCEL_MANUAL_DELIVERY",
                null,
                1
        ));

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
        assertThat(state.getCompletedAt()).isNotNull();
        verify(vkMessageProducer, never()).resendCommand(any());
    }

    @Test
    void ignoresStaleManualRetryAndStaleDeliveryResult() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.DELIVERY_FAILED);
        state.setResponseMode(WorkflowResponseMode.MANUAL);
        state.setDeliveryAttempt(2);
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        orchestrator.handleCuratorIntakeDecision(new CuratorIntakeDecisionEvent(
                requestId,
                "RETRY_MANUAL_DELIVERY",
                null,
                1
        ));

        verify(vkMessageProducer, never()).resendCommand(any());
        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.DELIVERY_FAILED);

        state.setStatus(WorkflowStatus.SENDING_TO_STUDENT);
        orchestrator.handleVkDeliveryResult(
                new VkMessageDeliveryResultEvent(
                        requestId,
                        false,
                        null,
                        "Late failure",
                        1
                )
        );

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.SENDING_TO_STUDENT);
        verify(notificationProducer, never())
                .sendManualDeliveryNotification(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void completesCompensationAfterSuccessfulRefund() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.REFUND_PENDING);
        state.setCreditsToCharge(new BigDecimal("1000"));
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleBillingRefundResult(new BillingRefundResultEvent(
                requestId,
                "REFUNDED",
                new BigDecimal("1000"),
                new BigDecimal("5000"),
                null
        ));

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.DELIVERY_FAILED_REFUNDED);
        assertThat(state.getRefundedCredits()).isEqualByComparingTo("1000");
        assertThat(state.getRefundedAt()).isNotNull();
        assertThat(state.getRefundError()).isNull();
    }

    @Test
    void marksCompensationFailedWhenRefundIsRejected() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.REFUND_PENDING);
        state.setCreditsToCharge(new BigDecimal("1000"));
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleBillingRefundResult(new BillingRefundResultEvent(
                requestId,
                "REJECTED",
                BigDecimal.ZERO,
                null,
                "Original billing transaction not found"
        ));

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.REFUND_FAILED);
        assertThat(state.getRefundError())
                .isEqualTo("Original billing transaction not found");
        assertThat(state.getRefundedCredits()).isNull();
    }

    @Test
    void ignoresRepeatedRefundResult() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(
                requestId,
                WorkflowStatus.DELIVERY_FAILED_REFUNDED
        );
        state.setRefundedCredits(new BigDecimal("1000"));
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleBillingRefundResult(new BillingRefundResultEvent(
                requestId,
                "REFUNDED",
                new BigDecimal("1000"),
                new BigDecimal("5000"),
                null
        ));

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.DELIVERY_FAILED_REFUNDED);
        assertThat(state.getRefundedCredits()).isEqualByComparingTo("1000");
    }

    @Test
    void ignoresRepeatedVkDeliveryResult() {
        UUID requestId = UUID.randomUUID();
        WorkflowState state = workflow(requestId, WorkflowStatus.COMPLETED);
        state.setVkMessageId(555L);
        when(workflowStateRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(state));

        orchestrator.handleVkDeliveryResult(
                new VkMessageDeliveryResultEvent(requestId, false, null, "Late error", 1)
        );

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(state.getVkMessageId()).isEqualTo(555L);
        assertThat(state.getDeliveryError()).isNull();
    }

    @Test
    void ignoresVkDeliveryResultOwnedByAnotherFlow() {
        UUID requestId = UUID.randomUUID();
        when(workflowStateRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.empty());

        orchestrator.handleVkDeliveryResult(
                new VkMessageDeliveryResultEvent(
                        requestId,
                        true,
                        555L,
                        null,
                        1
                )
        );

        verify(workflowStateRepository, never()).save(any());
        verifyNoInteractions(studentConversationProducer);
    }

    private VkMessageEvent vkEvent(UUID requestId) {
        return new VkMessageEvent(
                requestId,
                "200",
                "300",
                "Question",
                "100",
                1L,
                List.of()
        );
    }

    private WorkflowState workflow(UUID requestId, WorkflowStatus status) {
        return WorkflowState.builder()
                .requestId(requestId)
                .vkChatId("200")
                .vkUserId("300")
                .vkGroupId("100")
                .studentQuestion("Question")
                .status(status)
                .deliveryAttempt(1)
                .minimumCharge(new BigDecimal("100"))
                .photoUrls(List.of())
                .build();
    }
}
