package ru.itmo.nemat.orchestrator.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.orchestrator.model.VkGroupPrompt;
import ru.itmo.nemat.orchestrator.repository.VkGroupPromptRepository;
import ru.itmo.nemat.orchestrator.dto.*;
import ru.itmo.nemat.orchestrator.model.WorkflowState;
import ru.itmo.nemat.orchestrator.model.WorkflowResponseMode;
import ru.itmo.nemat.orchestrator.model.WorkflowStatus;
import ru.itmo.nemat.orchestrator.producer.AiCommandProducer;
import ru.itmo.nemat.orchestrator.producer.BalanceReleaseCommandProducer;
import ru.itmo.nemat.orchestrator.producer.BalanceReservationCommandProducer;
import ru.itmo.nemat.orchestrator.producer.BillingCommandProducer;
import ru.itmo.nemat.orchestrator.producer.BillingRefundCommandProducer;
import ru.itmo.nemat.orchestrator.producer.CuratorRequestProducer;
import ru.itmo.nemat.orchestrator.producer.CuratorIntakeRequestProducer;
import ru.itmo.nemat.orchestrator.producer.CuratorSystemNotificationProducer;
import ru.itmo.nemat.orchestrator.producer.StudentConversationProducer;
import ru.itmo.nemat.orchestrator.producer.VkMessageProducer;
import ru.itmo.nemat.orchestrator.repository.WorkflowStateRepository;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowOrchestrator {

    private final WorkflowStateRepository workflowStateRepository;
    private final VkGroupPromptRepository promptRepository;
    private final AiCommandProducer aiCommandProducer;
    private final BalanceReservationCommandProducer balanceReservationCommandProducer;
    private final BalanceReleaseCommandProducer balanceReleaseCommandProducer;
    private final CuratorIntakeRequestProducer curatorIntakeRequestProducer;
    private final CuratorRequestProducer curatorRequestProducer;
    private final BillingCommandProducer billingCommandProducer;
    private final BillingRefundCommandProducer billingRefundCommandProducer;
    private final CuratorSystemNotificationProducer notificationProducer;
    private final VkMessageProducer vkMessageProducer;
    private final StudentConversationProducer studentConversationProducer;
    private final BillingPricingService billingPricingService;

    @Transactional
    public void startWorkflow(VkMessageEvent event) {
        if (workflowStateRepository.existsById(event.requestId())) {
            log.info("[{}] Duplicate VK event ignored", event.requestId());
            return;
        }

        WorkflowState state = WorkflowState.builder()
                .requestId(event.requestId())
                .vkChatId(event.vkChatId())
                .vkUserId(effectiveVkUserId(event.vkUserId(), event.vkChatId()))
                .vkGroupId(event.vkGroupId())
                .studentQuestion(event.text())
                .status(WorkflowStatus.RECEIVED)
                .photoUrls(event.photoUrls())
                .build();

        workflowStateRepository.saveAndFlush(state);

        state.setStatus(WorkflowStatus.AWAITING_CURATOR_ACTION);
        workflowStateRepository.save(state);

        curatorIntakeRequestProducer.send(new CuratorIntakeRequest(
                state.getRequestId(),
                state.getVkGroupId(),
                state.getStudentQuestion(),
                state.getPhotoUrls()
        ));
    }

    @Transactional
    public void handleCuratorIntakeDecision(CuratorIntakeDecisionEvent event) {
        WorkflowState state = workflowStateRepository.findByIdForUpdate(event.requestId())
                .orElseThrow(() -> new IllegalArgumentException("Not found"));

        if ("RETRY_MANUAL_DELIVERY".equals(event.action())) {
            retryManualDelivery(state, event);
            return;
        }

        if ("CANCEL_MANUAL_DELIVERY".equals(event.action())) {
            cancelManualDelivery(state, event);
            return;
        }

        if (state.getStatus() != WorkflowStatus.AWAITING_CURATOR_ACTION) {
            log.info(
                    "[{}] Duplicate or stale curator intake decision ignored in status {}",
                    event.requestId(),
                    state.getStatus()
            );
            return;
        }

        if ("SEND_TO_AI".equals(event.action())) {
            startAiProcessing(state);
            return;
        }

        if ("MANUAL_ANSWER".equals(event.action())) {
            String manualAnswer = event.manualAnswer() == null
                    ? ""
                    : event.manualAnswer().strip();
            if (manualAnswer.isBlank()) {
                throw new IllegalArgumentException("Manual answer must not be blank");
            }

            state.setResponseMode(WorkflowResponseMode.MANUAL);
            state.setAiSuggestedAnswer(manualAnswer);
            state.setDeliveryAttempt(1);
            state.setDeliveryError(null);
            state.setCompletedAt(null);
            state.setStatus(WorkflowStatus.SENDING_TO_STUDENT);
            workflowStateRepository.save(state);
            vkMessageProducer.sendCommand(new SendVkMessageCommand(
                    state.getRequestId(),
                    state.getVkChatId(),
                    state.getVkGroupId(),
                    manualAnswer,
                    state.getDeliveryAttempt()
            ));
            return;
        }

        log.warn(
                "[{}] Unsupported curator intake action {}",
                event.requestId(),
                event.action()
        );
        throw new IllegalArgumentException(
                "Unsupported curator intake action: " + event.action()
        );
    }

    private void retryManualDelivery(
            WorkflowState state,
            CuratorIntakeDecisionEvent event
    ) {
        if (!isCurrentManualDeliveryFailure(state, event.deliveryAttempt())) {
            log.info(
                    "[{}] Stale manual delivery retry ignored in status {}, attempt {}",
                    event.requestId(),
                    state.getStatus(),
                    event.deliveryAttempt()
            );
            return;
        }

        state.setDeliveryAttempt(state.getDeliveryAttempt() + 1);
        state.setDeliveryError(null);
        state.setCompletedAt(null);
        state.setStatus(WorkflowStatus.SENDING_TO_STUDENT);
        workflowStateRepository.save(state);
        vkMessageProducer.resendCommand(new SendVkMessageCommand(
                state.getRequestId(),
                state.getVkChatId(),
                state.getVkGroupId(),
                state.getAiSuggestedAnswer(),
                state.getDeliveryAttempt()
        ));
    }

    private void cancelManualDelivery(
            WorkflowState state,
            CuratorIntakeDecisionEvent event
    ) {
        if (!isCurrentManualDeliveryFailure(state, event.deliveryAttempt())) {
            log.info(
                    "[{}] Stale manual delivery cancellation ignored in status {}, attempt {}",
                    event.requestId(),
                    state.getStatus(),
                    event.deliveryAttempt()
            );
            return;
        }

        state.setStatus(WorkflowStatus.CANCELLED);
        state.setCompletedAt(Instant.now());
        workflowStateRepository.save(state);
        log.info(
                "[{}] Manual delivery cancelled after attempt {}",
                state.getRequestId(),
                state.getDeliveryAttempt()
        );
    }

    private boolean isCurrentManualDeliveryFailure(
            WorkflowState state,
            Integer deliveryAttempt
    ) {
        return state.getStatus() == WorkflowStatus.DELIVERY_FAILED
                && state.getResponseMode() == WorkflowResponseMode.MANUAL
                && deliveryAttempt != null
                && deliveryAttempt == state.getDeliveryAttempt();
    }

    private void startAiProcessing(WorkflowState state) {
        BigDecimal minimumCharge = billingPricingService.minimumCharge();
        BigDecimal reservedCredits = billingPricingService.reservationCredits();
        Instant reservationExpiresAt = billingPricingService.reservationExpiresAt();

        state.setResponseMode(WorkflowResponseMode.AI);
        state.setMinimumCharge(minimumCharge);
        state.setReservedCredits(reservedCredits);
        state.setReservationExpiresAt(reservationExpiresAt);
        state.setReservationError(null);
        state.setStatus(WorkflowStatus.RESERVATION_PENDING);
        workflowStateRepository.save(state);

        balanceReservationCommandProducer.send(new BalanceReservationCommand(
                state.getRequestId(),
                state.getVkGroupId(),
                reservedCredits,
                reservationExpiresAt
        ));
    }

    @Transactional
    public void handleBalanceReservationResult(BalanceReservationResultEvent event) {
        WorkflowState state = workflowStateRepository.findByIdForUpdate(event.requestId())
                .orElseThrow(() -> new IllegalArgumentException("Not found"));

        if (state.getStatus() != WorkflowStatus.RESERVATION_PENDING) {
            log.info(
                    "[{}] Duplicate or stale reservation result ignored in status {}",
                    event.requestId(),
                    state.getStatus()
            );
            return;
        }

        if (state.getReservedCredits() == null
                || event.reservedCredits() == null
                || event.reservedCredits().compareTo(state.getReservedCredits()) != 0
                || !java.util.Objects.equals(
                        event.expiresAt(),
                        state.getReservationExpiresAt()
                )) {
            throw new IllegalStateException("Balance reservation parameters mismatch");
        }

        state.setAvailableBalanceAfterReservation(event.availableBalance());
        if (!"RESERVED".equals(event.status())) {
            state.setReservationError(normalizeReservationError(event.errorMessage()));
            state.setStatus(WorkflowStatus.RESERVATION_BLOCKED);
            workflowStateRepository.save(state);
            sendNotification(
                    state,
                    "RESERVATION_BLOCKED",
                    state.getReservationError()
            );
            log.info(
                    "[{}] AI generation blocked by reservation: {}",
                    event.requestId(),
                    state.getReservationError()
            );
            return;
        }

        if (event.balance() == null
                || event.availableBalance() == null
                || event.balance().compareTo(event.reservedCredits()) < 0) {
            throw new IllegalStateException("Balance reservation result is inconsistent");
        }

        state.setReservationError(null);
        state.setStatus(WorkflowStatus.AI_PROCESSING);
        workflowStateRepository.save(state);

        String systemPrompt = promptRepository.findById(state.getVkGroupId())
                .map(VkGroupPrompt::getSystemPrompt)
                .orElse("You are an experienced teacher. Answer clearly and in detail.");

        aiCommandProducer.sendCommand(new AiGenerationCommand(
                state.getRequestId(),
                state.getVkChatId(),
                state.getVkUserId(),
                state.getVkGroupId(),
                state.getStudentQuestion(),
                state.getPhotoUrls(),
                systemPrompt
        ));
    }

    @Transactional
    public void handleAiResponse(AiAnswerGeneratedEvent event) {
        WorkflowState state = workflowStateRepository.findByIdForUpdate(event.requestId())
                .orElseThrow(() -> new IllegalArgumentException("Not found"));

        if (state.getStatus() != WorkflowStatus.AI_PROCESSING) {
            log.info(
                    "[{}] Duplicate or stale AI result ignored in status {}",
                    event.requestId(),
                    state.getStatus()
            );
            return;
        }

        state.setAiSuggestedAnswer(event.answerText());
        state.setAiError(null);
        state.setAiFailedAt(null);
        state.setTokensUsed(normalizeTokensUsed(event.tokensUsed()));
        state.setProviderCostUsd(normalizeProviderCost(event.providerCostUsd()));
        state.setCreditsPerUsd(billingPricingService.creditsPerUsd());
        state.setMinimumCharge(billingPricingService.minimumCharge());
        state.setCreditsToCharge(
                billingPricingService.calculate(state.getProviderCostUsd())
        );
        state.setBillingError(null);
        state.setStatus(WorkflowStatus.AWAITING_APPROVAL);
        workflowStateRepository.save(state);

        CuratorApprovalRequest approvalRequest = new CuratorApprovalRequest(
                state.getRequestId(),
                state.getVkGroupId(),
                state.getStudentQuestion(),
                state.getAiSuggestedAnswer(),
                state.getPhotoUrls(),
                state.getTokensUsed(),
                state.getCreditsToCharge()
        );

        curatorRequestProducer.sendApprovalRequest(approvalRequest);
    }

    @Transactional
    public void handleAiFailure(AiGenerationFailedEvent event) {
        WorkflowState state = workflowStateRepository.findByIdForUpdate(event.requestId())
                .orElseThrow(() -> new IllegalArgumentException("Not found"));

        if (state.getStatus() != WorkflowStatus.AI_PROCESSING) {
            log.info(
                    "[{}] Duplicate or stale AI failure ignored in status {}",
                    event.requestId(),
                    state.getStatus()
            );
            return;
        }

        state.setAiError(normalizeAiError(event.errorMessage()));
        state.setAiFailedAt(event.failedAt() == null ? Instant.now() : event.failedAt());
        state.setStatus(WorkflowStatus.AI_FAILED);
        workflowStateRepository.save(state);
        balanceReleaseCommandProducer.send(new BalanceReleaseCommand(
                state.getRequestId(),
                state.getAiError()
        ));
        sendNotification(state, "AI_FAILED", state.getAiError());
        log.error("[{}] AI generation failed: {}", event.requestId(), state.getAiError());
    }

    @Transactional
    public void handleCuratorDecision(CuratorDecisionEvent event) {
        WorkflowState state = workflowStateRepository.findByIdForUpdate(event.requestId())
                .orElseThrow(() -> new IllegalArgumentException("Not found"));

        if (!"APPROVED".equals(event.status()) && !"REJECTED".equals(event.status())) {
            log.warn("[{}] Unsupported curator decision status {}", event.requestId(), event.status());
            return;
        }

        if (state.getStatus() != WorkflowStatus.AWAITING_APPROVAL) {
            log.info(
                    "[{}] Duplicate or stale curator decision ignored in status {}",
                    event.requestId(),
                    state.getStatus()
            );
            return;
        }

        if ("REJECTED".equals(event.status())) {
            state.setStatus(WorkflowStatus.REJECTED);
            state.setCompletedAt(Instant.now());
            workflowStateRepository.save(state);
            balanceReleaseCommandProducer.send(new BalanceReleaseCommand(
                    state.getRequestId(),
                    "Curator rejected AI answer"
            ));
            log.info("[{}] Curator rejected AI answer", event.requestId());
            return;
        }

        if (event.finalAnswer() != null && !event.finalAnswer().isBlank()) {
            state.setAiSuggestedAnswer(event.finalAnswer());
        }

        state.setStatus(WorkflowStatus.BILLING_PENDING);
        state.setBillingError(null);
        state.setDeliveryError(null);
        state.setCompletedAt(null);
        workflowStateRepository.save(state);

        billingCommandProducer.sendCharge(new BillingChargeCommand(
                state.getRequestId(),
                state.getVkGroupId(),
                state.getTokensUsed(),
                state.getProviderCostUsd(),
                state.getCreditsToCharge(),
                state.getCreditsPerUsd(),
                state.getMinimumCharge()
        ));
    }

    @Transactional
    public void handleBillingResult(BillingResultEvent event) {
        WorkflowState state = workflowStateRepository.findByIdForUpdate(event.requestId())
                .orElseThrow(() -> new IllegalArgumentException("Not found"));

        if (state.getStatus() != WorkflowStatus.BILLING_PENDING) {
            log.info(
                    "[{}] Duplicate or stale billing result ignored in status {}",
                    event.requestId(),
                    state.getStatus()
            );
            return;
        }

        if (!"CHARGED".equals(event.status())) {
            state.setBillingError(normalizeBillingError(event.errorMessage()));
            state.setStatus(WorkflowStatus.BILLING_FAILED);
            workflowStateRepository.save(state);
            sendNotification(state, "BILLING_FAILED", state.getBillingError());
            log.warn("[{}] Billing failed: {}", event.requestId(), state.getBillingError());
            return;
        }

        if (event.chargedCredits() == null
                || event.chargedCredits().compareTo(state.getCreditsToCharge()) != 0) {
            throw new IllegalStateException(
                    "Billing amount mismatch: expected " + state.getCreditsToCharge()
                            + ", charged " + event.chargedCredits()
            );
        }

        state.setBillingError(null);
        state.setDeliveryAttempt(1);
        state.setStatus(WorkflowStatus.SENDING_TO_STUDENT);
        workflowStateRepository.save(state);

        vkMessageProducer.sendCommand(new SendVkMessageCommand(
                state.getRequestId(),
                state.getVkChatId(),
                state.getVkGroupId(),
                state.getAiSuggestedAnswer(),
                state.getDeliveryAttempt()
        ));
    }

    @Transactional
    public void handleVkDeliveryResult(VkMessageDeliveryResultEvent event) {
        WorkflowState state = workflowStateRepository
                .findByIdForUpdate(event.requestId())
                .orElse(null);
        if (state == null) {
            log.debug(
                    "[{}] VK delivery result belongs to a non-workflow message",
                    event.requestId()
            );
            return;
        }

        if (state.getStatus() != WorkflowStatus.SENDING_TO_STUDENT) {
            log.info(
                    "[{}] Duplicate or stale VK delivery result ignored in status {}",
                    event.requestId(),
                    state.getStatus()
            );
            return;
        }
        int resultAttempt = event.deliveryAttempt() <= 0
                ? 1
                : event.deliveryAttempt();
        int expectedAttempt = state.getDeliveryAttempt() <= 0
                ? 1
                : state.getDeliveryAttempt();
        if (resultAttempt != expectedAttempt) {
            log.info(
                    "[{}] Stale VK delivery result for attempt {} ignored; current attempt is {}",
                    event.requestId(),
                    resultAttempt,
                    expectedAttempt
            );
            return;
        }
        if (state.getDeliveryAttempt() <= 0) {
            state.setDeliveryAttempt(expectedAttempt);
        }

        if (event.success()) {
            state.setVkMessageId(event.vkMessageId());
            state.setDeliveryError(null);
            Instant completedAt = Instant.now();
            state.setCompletedAt(completedAt);
            state.setStatus(WorkflowStatus.COMPLETED);
            studentConversationProducer.sendDeliveredAnswer(
                    new StudentConversationMessageEvent(
                            state.getRequestId(),
                            "ASSISTANT",
                            state.getVkChatId(),
                            state.getVkUserId(),
                            state.getVkGroupId(),
                            null,
                            null,
                            null,
                            state.getAiSuggestedAnswer(),
                            java.util.List.of(),
                            event.vkMessageId(),
                            "ORCHESTRATOR",
                            completedAt
                    )
            );
            if (state.getResponseMode() == WorkflowResponseMode.MANUAL) {
                sendManualDeliveryNotification(
                        state,
                        "MANUAL_DELIVERY_SUCCEEDED",
                        null
                );
            }
            log.info("[{}] Workflow completed with VK message {}", event.requestId(), event.vkMessageId());
        } else {
            state.setDeliveryError(normalizeDeliveryError(event.errorMessage()));
            state.setCompletedAt(null);
            if (state.getResponseMode() == WorkflowResponseMode.MANUAL) {
                state.setStatus(WorkflowStatus.DELIVERY_FAILED);
                workflowStateRepository.save(state);
                sendManualDeliveryNotification(
                        state,
                        "MANUAL_DELIVERY_FAILED",
                        state.getDeliveryError()
                );
                log.warn(
                        "[{}] Manual VK delivery failed: {}",
                        event.requestId(),
                        state.getDeliveryError()
                );
                return;
            }
            state.setRefundError(null);
            state.setRefundedAt(null);
            state.setStatus(WorkflowStatus.REFUND_PENDING);
            billingRefundCommandProducer.sendRefund(new BillingRefundCommand(
                    state.getRequestId(),
                    state.getDeliveryError()
            ));
            log.warn(
                    "[{}] VK delivery failed, billing refund requested: {}",
                    event.requestId(),
                    state.getDeliveryError()
            );
        }

        workflowStateRepository.save(state);
    }

    private void sendManualDeliveryNotification(
            WorkflowState state,
            String type,
            String details
    ) {
        CuratorSystemNotificationCommand command =
                new CuratorSystemNotificationCommand(
                        state.getRequestId(),
                        state.getVkGroupId(),
                        type,
                        state.getStatus().name(),
                        details,
                        state.getDeliveryAttempt()
                );
        notificationProducer.sendManualDeliveryNotification(
                command,
                state.getDeliveryAttempt()
        );
    }

    @Transactional
    public void handleBillingRefundResult(BillingRefundResultEvent event) {
        WorkflowState state = workflowStateRepository.findByIdForUpdate(event.requestId())
                .orElseThrow(() -> new IllegalArgumentException("Not found"));

        if (state.getStatus() != WorkflowStatus.REFUND_PENDING) {
            log.info(
                    "[{}] Duplicate or stale billing refund result ignored in status {}",
                    event.requestId(),
                    state.getStatus()
            );
            return;
        }

        if (!"REFUNDED".equals(event.status())) {
            state.setRefundError(normalizeRefundError(event.errorMessage()));
            state.setStatus(WorkflowStatus.REFUND_FAILED);
            workflowStateRepository.save(state);
            sendNotification(state, "REFUND_FAILED", state.getRefundError());
            log.error("[{}] Billing refund failed: {}", event.requestId(), state.getRefundError());
            return;
        }

        if (event.refundedCredits() == null
                || event.refundedCredits().compareTo(state.getCreditsToCharge()) != 0) {
            throw new IllegalStateException(
                    "Refund amount mismatch: expected " + state.getCreditsToCharge()
                            + ", refunded " + event.refundedCredits()
            );
        }

        state.setRefundedCredits(event.refundedCredits());
        state.setRefundError(null);
        state.setRefundedAt(Instant.now());
        state.setStatus(WorkflowStatus.DELIVERY_FAILED_REFUNDED);
        workflowStateRepository.save(state);
        log.info(
                "[{}] Delivery failed and {} credits were refunded",
                event.requestId(),
                event.refundedCredits()
        );
    }

    private String normalizeDeliveryError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Unknown VK delivery error";
        }
        return errorMessage.length() <= 1000 ? errorMessage : errorMessage.substring(0, 1000);
    }

    private String effectiveVkUserId(String vkUserId, String vkChatId) {
        return vkUserId == null || vkUserId.isBlank()
                ? vkChatId
                : vkUserId;
    }

    private int normalizeTokensUsed(Integer tokensUsed) {
        if (tokensUsed == null) {
            return 0;
        }
        if (tokensUsed < 0) {
            throw new IllegalArgumentException("AI token usage must not be negative");
        }
        return tokensUsed;
    }

    private java.math.BigDecimal normalizeProviderCost(java.math.BigDecimal providerCostUsd) {
        if (providerCostUsd == null) {
            throw new IllegalArgumentException("AI provider cost is required");
        }
        if (providerCostUsd.signum() < 0) {
            throw new IllegalArgumentException("AI provider cost must not be negative");
        }
        return providerCostUsd;
    }

    private String normalizeBillingError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Billing rejected";
        }
        return errorMessage.length() <= 1000 ? errorMessage : errorMessage.substring(0, 1000);
    }

    private String normalizeAiError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "AI generation failed";
        }
        return errorMessage.length() <= 1000
                ? errorMessage
                : errorMessage.substring(0, 1000);
    }

    private String normalizeReservationError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Balance reservation rejected";
        }
        return errorMessage.length() <= 1000
                ? errorMessage
                : errorMessage.substring(0, 1000);
    }

    private String normalizeRefundError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Billing refund rejected";
        }
        return errorMessage.length() <= 1000 ? errorMessage : errorMessage.substring(0, 1000);
    }

    private void sendNotification(
            WorkflowState state,
            String type,
            String details
    ) {
        notificationProducer.send(new CuratorSystemNotificationCommand(
                state.getRequestId(),
                state.getVkGroupId(),
                type,
                state.getStatus().name(),
                details,
                null
        ));
    }
}
