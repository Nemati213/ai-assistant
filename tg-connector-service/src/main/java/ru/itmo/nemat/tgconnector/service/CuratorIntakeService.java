package ru.itmo.nemat.tgconnector.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.dto.CuratorIntakeDecisionEvent;
import ru.itmo.nemat.tgconnector.dto.CuratorIntakeRequest;
import ru.itmo.nemat.tgconnector.model.CuratorIntakeOutboxEvent;
import ru.itmo.nemat.tgconnector.model.CuratorIntakeRequestState;
import ru.itmo.nemat.tgconnector.model.CuratorIntakeStatus;
import ru.itmo.nemat.tgconnector.repository.CuratorIntakeOutboxRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorIntakeRequestRepository;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CuratorIntakeService {

    private static final String SEND_TO_AI = "SEND_TO_AI";
    private static final String MANUAL_ANSWER = "MANUAL_ANSWER";
    private static final String RETRY_MANUAL_DELIVERY = "RETRY_MANUAL_DELIVERY";
    private static final String CANCEL_MANUAL_DELIVERY = "CANCEL_MANUAL_DELIVERY";

    private final CuratorIntakeRequestRepository requestRepository;
    private final CuratorIntakeOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public Optional<IntakeView> prepare(CuratorIntakeRequest intake, Long tgChatId) {
        CuratorIntakeRequestState state = requestRepository
                .findByIdForUpdate(intake.requestId())
                .map(existing -> validateReplay(existing, intake, tgChatId))
                .orElseGet(() -> createState(intake, tgChatId));

        if (state.getStatus() == CuratorIntakeStatus.DECISION_QUEUED
                || state.getIntakeMessageId() != null) {
            return Optional.empty();
        }
        return Optional.of(toView(state));
    }

    @Transactional
    public void markDelivered(UUID requestId, Long tgChatId, Integer messageId) {
        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (state.getStatus() == CuratorIntakeStatus.AWAITING_ACTION
                && state.getIntakeMessageId() == null) {
            state.markDelivered(messageId, Instant.now());
        }
    }

    @Transactional
    public boolean queueAi(UUID requestId, Long tgChatId) {
        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (state.getStatus() != CuratorIntakeStatus.AWAITING_ACTION) {
            return false;
        }
        enqueueDecision(state, SEND_TO_AI, null);
        return true;
    }

    @Transactional
    public Optional<IntakeView> beginManualReply(UUID requestId, Long tgChatId) {
        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (state.getStatus() == CuratorIntakeStatus.AWAITING_ACTION) {
            state.beginManualReply(Instant.now());
        } else if (state.getStatus() != CuratorIntakeStatus.AWAITING_MANUAL_REPLY
                || state.getManualPromptMessageId() != null) {
            return Optional.empty();
        }
        return Optional.of(toView(state));
    }

    @Transactional
    public void attachManualPrompt(
            UUID requestId,
            Long tgChatId,
            Integer promptMessageId
    ) {
        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (state.getStatus() != CuratorIntakeStatus.AWAITING_MANUAL_REPLY
                || state.getManualPromptMessageId() != null) {
            throw new IllegalStateException("Manual reply request is no longer active");
        }
        state.attachManualPrompt(promptMessageId, Instant.now());
    }

    @Transactional
    public void cancelManualReply(UUID requestId, Long tgChatId) {
        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (state.getStatus() == CuratorIntakeStatus.AWAITING_MANUAL_REPLY) {
            state.cancelManualReply(Instant.now());
        }
    }

    @Transactional
    public boolean reopenAfterManualCancellation(
            UUID requestId,
            Long tgChatId,
            Integer promptMessageId
    ) {
        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (state.getStatus() != CuratorIntakeStatus.AWAITING_MANUAL_REPLY
                || !Objects.equals(
                        state.getManualPromptMessageId(),
                        promptMessageId
                )) {
            return false;
        }
        state.reopenAfterManualCancellation(Instant.now());
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<IntakeView> findManualReply(
            Long tgChatId,
            Integer replyToMessageId
    ) {
        return requestRepository
                .findByTgChatIdAndManualPromptMessageIdAndStatus(
                        tgChatId,
                        replyToMessageId,
                        CuratorIntakeStatus.AWAITING_MANUAL_REPLY
                )
                .map(this::toView);
    }

    @Transactional(readOnly = true)
    public Optional<IntakeView> findView(UUID requestId, Long tgChatId) {
        return requestRepository.findById(requestId)
                .filter(state -> state.getTgChatId().equals(tgChatId))
                .map(this::toView);
    }

    @Transactional
    public boolean completeManualReply(
            UUID requestId,
            Long tgChatId,
            Integer promptMessageId,
            String manualAnswer
    ) {
        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (state.getStatus() != CuratorIntakeStatus.AWAITING_MANUAL_REPLY
                || !Objects.equals(state.getManualPromptMessageId(), promptMessageId)) {
            return false;
        }
        enqueueDecision(state, MANUAL_ANSWER, manualAnswer);
        return true;
    }

    @Transactional
    public Optional<IntakeView> prepareManualDeliveryFailure(
            UUID requestId,
            Long tgChatId,
            int deliveryAttempt,
            String error
    ) {
        if (deliveryAttempt <= 0) {
            throw new IllegalArgumentException("Delivery attempt must be positive");
        }

        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (deliveryAttempt < state.getDeliveryAttempt()) {
            return Optional.empty();
        }
        if (deliveryAttempt == state.getDeliveryAttempt()) {
            if (state.getStatus() == CuratorIntakeStatus.MANUAL_DELIVERY_FAILED
                    && state.getFailureMessageId() == null) {
                return Optional.of(toView(state));
            }
            return Optional.empty();
        }
        if (state.getStatus() != CuratorIntakeStatus.DECISION_QUEUED
                && state.getStatus() != CuratorIntakeStatus.RECOVERY_ACTION_QUEUED) {
            return Optional.empty();
        }

        state.markManualDeliveryFailed(
                deliveryAttempt,
                normalizeDeliveryError(error),
                Instant.now()
        );
        return Optional.of(toView(state));
    }

    @Transactional
    public void markManualDeliveryFailureDelivered(
            UUID requestId,
            Long tgChatId,
            int deliveryAttempt,
            Integer messageId
    ) {
        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (state.getStatus() == CuratorIntakeStatus.MANUAL_DELIVERY_FAILED
                && state.getDeliveryAttempt() == deliveryAttempt
                && state.getFailureMessageId() == null) {
            state.markFailureDelivered(messageId, Instant.now());
        }
    }

    @Transactional
    public boolean queueManualDeliveryAction(
            UUID requestId,
            Long tgChatId,
            int deliveryAttempt,
            boolean cancel
    ) {
        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (state.getStatus() != CuratorIntakeStatus.MANUAL_DELIVERY_FAILED
                || state.getDeliveryAttempt() != deliveryAttempt) {
            return false;
        }

        String action = cancel
                ? CANCEL_MANUAL_DELIVERY
                : RETRY_MANUAL_DELIVERY;
        enqueueDecision(
                state,
                action,
                null,
                deliveryAttempt,
                state.getRequestId() + ":MANUAL_DELIVERY:" + deliveryAttempt
        );
        state.queueRecoveryAction(cancel, Instant.now());
        return true;
    }

    @Transactional
    public Optional<IntakeView> prepareManualDeliverySuccess(
            UUID requestId,
            Long tgChatId,
            int deliveryAttempt
    ) {
        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (deliveryAttempt <= state.getDeliveryAttempt()
                || (state.getStatus() != CuratorIntakeStatus.DECISION_QUEUED
                && state.getStatus() != CuratorIntakeStatus.RECOVERY_ACTION_QUEUED)) {
            return Optional.empty();
        }
        return Optional.of(toView(state));
    }

    @Transactional
    public void markManualDeliverySuccessDelivered(
            UUID requestId,
            Long tgChatId,
            int deliveryAttempt
    ) {
        CuratorIntakeRequestState state = requireOwnedForUpdate(requestId, tgChatId);
        if (deliveryAttempt > state.getDeliveryAttempt()
                && (state.getStatus() == CuratorIntakeStatus.DECISION_QUEUED
                || state.getStatus() == CuratorIntakeStatus.RECOVERY_ACTION_QUEUED)) {
            state.markCompleted(deliveryAttempt, Instant.now());
        }
    }

    private void enqueueDecision(
            CuratorIntakeRequestState state,
            String action,
            String manualAnswer
    ) {
        enqueueDecision(
                state,
                action,
                manualAnswer,
                null,
                state.getRequestId() + ":INITIAL"
        );
        state.queueDecision(Instant.now());
    }

    private void enqueueDecision(
            CuratorIntakeRequestState state,
            String action,
            String manualAnswer,
            Integer deliveryAttempt,
            String deduplicationKey
    ) {
        CuratorIntakeDecisionEvent event = new CuratorIntakeDecisionEvent(
                state.getRequestId(),
                action,
                manualAnswer,
                deliveryAttempt
        );
        Instant now = Instant.now();
        outboxRepository.save(CuratorIntakeOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .requestId(state.getRequestId())
                .deduplicationKey(deduplicationKey)
                .payload(serialize(event))
                .createdAt(now)
                .attempts(0)
                .nextAttemptAt(now)
                .build());
    }

    private CuratorIntakeRequestState createState(
            CuratorIntakeRequest intake,
            Long tgChatId
    ) {
        Instant now = Instant.now();
        return requestRepository.save(CuratorIntakeRequestState.builder()
                .requestId(intake.requestId())
                .tgChatId(tgChatId)
                .vkGroupId(intake.vkGroupId())
                .studentQuestion(intake.studentQuestion())
                .status(CuratorIntakeStatus.AWAITING_ACTION)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private CuratorIntakeRequestState validateReplay(
            CuratorIntakeRequestState existing,
            CuratorIntakeRequest intake,
            Long tgChatId
    ) {
        if (!existing.getTgChatId().equals(tgChatId)
                || !existing.getVkGroupId().equals(intake.vkGroupId())
                || !existing.getStudentQuestion().equals(intake.studentQuestion())) {
            throw new IllegalStateException(
                    "Curator intake requestId was reused with different parameters"
            );
        }
        return existing;
    }

    private CuratorIntakeRequestState requireOwnedForUpdate(
            UUID requestId,
            Long tgChatId
    ) {
        CuratorIntakeRequestState state = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Curator intake request not found"
                ));
        if (!state.getTgChatId().equals(tgChatId)) {
            throw new IllegalArgumentException(
                    "Curator intake request belongs to another chat"
            );
        }
        return state;
    }

    private IntakeView toView(CuratorIntakeRequestState state) {
        return new IntakeView(
                state.getRequestId(),
                state.getTgChatId(),
                state.getStudentQuestion(),
                state.getIntakeMessageId(),
                state.getManualPromptMessageId(),
                state.getDeliveryAttempt(),
                state.getLastDeliveryError()
        );
    }

    private String normalizeDeliveryError(String error) {
        if (error == null || error.isBlank()) {
            return "VK не сообщил причину ошибки";
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    private String serialize(CuratorIntakeDecisionEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize curator intake decision",
                    exception
            );
        }
    }

    public record IntakeView(
            UUID requestId,
            Long tgChatId,
            String studentQuestion,
            Integer intakeMessageId,
            Integer manualPromptMessageId,
            int deliveryAttempt,
            String lastDeliveryError
    ) {
    }
}
