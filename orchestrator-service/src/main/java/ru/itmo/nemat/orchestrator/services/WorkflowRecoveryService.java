package ru.itmo.nemat.orchestrator.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.orchestrator.dto.AiGenerationCommand;
import ru.itmo.nemat.orchestrator.dto.BalanceReservationCommand;
import ru.itmo.nemat.orchestrator.dto.BillingChargeCommand;
import ru.itmo.nemat.orchestrator.dto.BillingRefundCommand;
import ru.itmo.nemat.orchestrator.dto.CuratorApprovalRequest;
import ru.itmo.nemat.orchestrator.dto.CuratorIntakeRequest;
import ru.itmo.nemat.orchestrator.dto.CuratorSystemNotificationCommand;
import ru.itmo.nemat.orchestrator.dto.SendVkMessageCommand;
import ru.itmo.nemat.orchestrator.model.VkGroupPrompt;
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

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowRecoveryService {

    private final WorkflowStateRepository workflowRepository;
    private final VkGroupPromptRepository promptRepository;
    private final BalanceReservationCommandProducer reservationProducer;
    private final AiCommandProducer aiProducer;
    private final CuratorIntakeRequestProducer curatorIntakeRequestProducer;
    private final CuratorRequestProducer curatorRequestProducer;
    private final BillingCommandProducer billingProducer;
    private final VkMessageProducer vkMessageProducer;
    private final BillingRefundCommandProducer refundProducer;
    private final CuratorSystemNotificationProducer notificationProducer;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recover(
            UUID requestId,
            WorkflowStatus expectedStatus,
            Instant cutoff,
            int maxAttempts
    ) {
        WorkflowState state = workflowRepository.findByIdForUpdate(requestId)
                .orElse(null);
        if (state == null
                || state.getStatus() != expectedStatus
                || state.getStatusChangedAt().isAfter(cutoff)
                || state.getRecoveryAttempts() >= maxAttempts) {
            return false;
        }

        switch (expectedStatus) {
            case AWAITING_CURATOR_ACTION -> resendIntakeRequest(state);
            case RESERVATION_PENDING -> resendReservation(state);
            case AI_PROCESSING -> resendAiGeneration(state);
            case AWAITING_APPROVAL -> resendApprovalRequest(state);
            case BILLING_PENDING -> resendBilling(state);
            case SENDING_TO_STUDENT -> resendVkMessage(state);
            case REFUND_PENDING -> resendRefund(state);
            default -> throw new IllegalArgumentException(
                    "Status is not recoverable: " + expectedStatus
            );
        }

        state.markRecoveryAttempt(Instant.now());
        log.warn(
                "[{}] Watchdog requeued workflow in status {}, recovery attempt {}",
                requestId,
                expectedStatus,
                state.getRecoveryAttempts()
        );
        return true;
    }

    private void resendIntakeRequest(WorkflowState state) {
        curatorIntakeRequestProducer.resend(new CuratorIntakeRequest(
                state.getRequestId(),
                state.getVkGroupId(),
                state.getStudentQuestion(),
                state.getPhotoUrls()
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean notifyRecoveryExhausted(
            UUID requestId,
            WorkflowStatus expectedStatus,
            int maxAttempts
    ) {
        WorkflowState state = workflowRepository.findByIdForUpdate(requestId)
                .orElse(null);
        if (state == null
                || state.getStatus() != expectedStatus
                || state.getRecoveryAttempts() < maxAttempts
                || state.getRecoveryExhaustedNotifiedAt() != null) {
            return false;
        }

        notificationProducer.send(new CuratorSystemNotificationCommand(
                state.getRequestId(),
                state.getVkGroupId(),
                "RECOVERY_EXHAUSTED_" + expectedStatus.name(),
                expectedStatus.name(),
                recoveryExhaustedDetails(expectedStatus),
                null
        ));
        state.markRecoveryExhaustedNotified(Instant.now());
        log.error(
                "[{}] Watchdog recovery exhausted in status {} after {} attempts",
                requestId,
                expectedStatus,
                state.getRecoveryAttempts()
        );
        return true;
    }

    private void resendReservation(WorkflowState state) {
        if (state.getReservedCredits() == null || state.getReservationExpiresAt() == null) {
            throw new IllegalStateException("Reservation parameters are missing");
        }
        reservationProducer.resend(new BalanceReservationCommand(
                state.getRequestId(),
                state.getVkGroupId(),
                state.getReservedCredits(),
                state.getReservationExpiresAt()
        ));
    }

    private void resendAiGeneration(WorkflowState state) {
        String systemPrompt = promptRepository.findById(state.getVkGroupId())
                .map(VkGroupPrompt::getSystemPrompt)
                .orElse("You are an experienced teacher. Answer clearly and in detail.");
        aiProducer.resendCommand(new AiGenerationCommand(
                state.getRequestId(),
                state.getVkChatId(),
                state.getVkUserId(),
                state.getVkGroupId(),
                state.getStudentQuestion(),
                state.getPhotoUrls(),
                systemPrompt
        ));
    }

    private void resendApprovalRequest(WorkflowState state) {
        requireAiResult(state);
        curatorRequestProducer.resendApprovalRequest(new CuratorApprovalRequest(
                state.getRequestId(),
                state.getVkGroupId(),
                state.getStudentQuestion(),
                state.getAiSuggestedAnswer(),
                state.getPhotoUrls(),
                state.getTokensUsed(),
                state.getCreditsToCharge()
        ));
    }

    private void resendBilling(WorkflowState state) {
        requireBillingParameters(state);
        billingProducer.resendCharge(new BillingChargeCommand(
                state.getRequestId(),
                state.getVkGroupId(),
                state.getTokensUsed(),
                state.getProviderCostUsd(),
                state.getCreditsToCharge(),
                state.getCreditsPerUsd(),
                state.getMinimumCharge()
        ));
    }

    private void resendVkMessage(WorkflowState state) {
        if (state.getAiSuggestedAnswer() == null || state.getAiSuggestedAnswer().isBlank()) {
            throw new IllegalStateException("Final answer is missing");
        }
        vkMessageProducer.resendCommand(new SendVkMessageCommand(
                state.getRequestId(),
                state.getVkChatId(),
                state.getVkGroupId(),
                state.getAiSuggestedAnswer(),
                state.getDeliveryAttempt()
        ));
    }

    private void resendRefund(WorkflowState state) {
        refundProducer.resendRefund(new BillingRefundCommand(
                state.getRequestId(),
                state.getDeliveryError() == null
                        ? "VK delivery failed"
                        : state.getDeliveryError()
        ));
    }

    private void requireAiResult(WorkflowState state) {
        if (state.getAiSuggestedAnswer() == null
                || state.getAiSuggestedAnswer().isBlank()
                || state.getTokensUsed() == null
                || state.getCreditsToCharge() == null) {
            throw new IllegalStateException("AI result is incomplete");
        }
    }

    private void requireBillingParameters(WorkflowState state) {
        requireAiResult(state);
        if (state.getProviderCostUsd() == null
                || state.getCreditsPerUsd() == null
                || state.getMinimumCharge() == null) {
            throw new IllegalStateException("Billing parameters are incomplete");
        }
    }

    private String recoveryExhaustedDetails(WorkflowStatus status) {
        if (status == WorkflowStatus.AWAITING_CURATOR_ACTION) {
            return "Вопрос долго ожидает выбора: отправить его в ИИ или ответить вручную";
        }
        if (status == WorkflowStatus.AWAITING_APPROVAL) {
            return "Вопрос долго ожидает решения куратора";
        }
        return "Автоматическое восстановление не завершило этап " + status.name();
    }
}
