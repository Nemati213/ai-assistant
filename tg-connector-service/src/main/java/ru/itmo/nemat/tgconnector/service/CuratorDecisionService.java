package ru.itmo.nemat.tgconnector.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.dto.CuratorApprovalRequest;
import ru.itmo.nemat.tgconnector.dto.CuratorDecisionEvent;
import ru.itmo.nemat.tgconnector.model.CuratorDecisionOutboxEvent;
import ru.itmo.nemat.tgconnector.model.CuratorDecisionRequest;
import ru.itmo.nemat.tgconnector.model.CuratorDecisionRequestStatus;
import ru.itmo.nemat.tgconnector.repository.CuratorDecisionOutboxRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorDecisionRequestRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CuratorDecisionService {

    private final CuratorDecisionRequestRepository requestRepository;
    private final CuratorDecisionOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public Optional<DecisionView> prepareApproval(
            CuratorApprovalRequest approval,
            Long tgChatId
    ) {
        CuratorDecisionRequest request = requestRepository.findByIdForUpdate(approval.requestId())
                .map(existing -> validateReplay(existing, approval, tgChatId))
                .orElseGet(() -> createRequest(approval, tgChatId));

        if (request.getStatus() == CuratorDecisionRequestStatus.DECISION_QUEUED
                || request.getApprovalMessageId() != null) {
            return Optional.empty();
        }
        return Optional.of(toView(request));
    }

    @Transactional
    public void markApprovalDelivered(
            UUID requestId,
            Long tgChatId,
            int revision,
            Integer messageId
    ) {
        CuratorDecisionRequest request = requireOwnedForUpdate(requestId, tgChatId);
        if (request.getStatus() == CuratorDecisionRequestStatus.AWAITING_DECISION
                && request.getRevision() == revision
                && request.getApprovalMessageId() == null) {
            request.markApprovalDelivered(messageId, Instant.now());
        }
    }

    @Transactional
    public Optional<DecisionView> beginEditing(
            UUID requestId,
            Long tgChatId,
            int revision
    ) {
        CuratorDecisionRequest request = requireOwnedForUpdate(requestId, tgChatId);
        if (request.getRevision() != revision) {
            return Optional.empty();
        }

        if (request.getStatus() == CuratorDecisionRequestStatus.AWAITING_DECISION) {
            request.beginEditing(Instant.now());
        } else if (request.getStatus() != CuratorDecisionRequestStatus.AWAITING_EDIT
                || request.getEditPromptMessageId() != null) {
            return Optional.empty();
        }
        return Optional.of(toView(request));
    }

    @Transactional
    public void attachEditPrompt(
            UUID requestId,
            Long tgChatId,
            int revision,
            Integer promptMessageId
    ) {
        CuratorDecisionRequest request = requireOwnedForUpdate(requestId, tgChatId);
        if (request.getStatus() != CuratorDecisionRequestStatus.AWAITING_EDIT
                || request.getRevision() != revision) {
            throw new IllegalStateException("Editing request is no longer active");
        }
        request.attachEditPrompt(promptMessageId, Instant.now());
    }

    @Transactional
    public void cancelEditing(UUID requestId, Long tgChatId, int revision) {
        CuratorDecisionRequest request = requireOwnedForUpdate(requestId, tgChatId);
        if (request.getStatus() == CuratorDecisionRequestStatus.AWAITING_EDIT
                && request.getRevision() == revision) {
            request.cancelEditing(Instant.now());
        }
    }

    @Transactional(readOnly = true)
    public Optional<DecisionView> findEditingReply(
            Long tgChatId,
            Integer replyToMessageId
    ) {
        return requestRepository.findByTgChatIdAndEditPromptMessageIdAndStatus(
                        tgChatId,
                        replyToMessageId,
                        CuratorDecisionRequestStatus.AWAITING_EDIT
                )
                .map(this::toView);
    }

    @Transactional
    public boolean completeEditing(
            UUID requestId,
            Long tgChatId,
            int revision,
            Integer editPromptMessageId,
            String editedAnswer,
            Integer approvalMessageId
    ) {
        CuratorDecisionRequest request = requireOwnedForUpdate(requestId, tgChatId);
        if (request.getStatus() != CuratorDecisionRequestStatus.AWAITING_EDIT
                || request.getRevision() != revision
                || !java.util.Objects.equals(
                        request.getEditPromptMessageId(),
                        editPromptMessageId
                )) {
            return false;
        }

        request.applyEditedAnswer(editedAnswer, approvalMessageId, Instant.now());
        return true;
    }

    @Transactional
    public boolean queueDecision(
            UUID requestId,
            Long tgChatId,
            int revision,
            String status
    ) {
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw new IllegalArgumentException("Unsupported curator decision: " + status);
        }

        CuratorDecisionRequest request = requireOwnedForUpdate(requestId, tgChatId);
        if (request.getStatus() != CuratorDecisionRequestStatus.AWAITING_DECISION
                || request.getRevision() != revision) {
            return false;
        }

        String finalAnswer = "APPROVED".equals(status)
                ? request.getCurrentAnswer()
                : null;
        CuratorDecisionEvent decision =
                new CuratorDecisionEvent(requestId, status, finalAnswer);
        Instant now = Instant.now();

        outboxRepository.save(CuratorDecisionOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .requestId(requestId)
                .payload(serialize(decision))
                .createdAt(now)
                .attempts(0)
                .nextAttemptAt(now)
                .build());
        request.queueDecision(now);
        return true;
    }

    private CuratorDecisionRequest createRequest(
            CuratorApprovalRequest approval,
            Long tgChatId
    ) {
        Instant now = Instant.now();
        return requestRepository.save(CuratorDecisionRequest.builder()
                .requestId(approval.requestId())
                .tgChatId(tgChatId)
                .vkGroupId(approval.vkGroupId())
                .studentQuestion(approval.studentQuestion())
                .currentAnswer(approval.aiSuggestedAnswer())
                .tokensUsed(approval.tokensUsed())
                .creditsToCharge(approval.creditsToCharge())
                .status(CuratorDecisionRequestStatus.AWAITING_DECISION)
                .revision(0)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private CuratorDecisionRequest validateReplay(
            CuratorDecisionRequest existing,
            CuratorApprovalRequest approval,
            Long tgChatId
    ) {
        if (!existing.getTgChatId().equals(tgChatId)
                || !existing.getVkGroupId().equals(approval.vkGroupId())
                || existing.getTokensUsed() != approval.tokensUsed()
                || existing.getCreditsToCharge().compareTo(approval.creditsToCharge()) != 0) {
            throw new IllegalStateException(
                    "Approval requestId was reused with different parameters"
            );
        }
        return existing;
    }

    private CuratorDecisionRequest requireOwnedForUpdate(UUID requestId, Long tgChatId) {
        CuratorDecisionRequest request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Curator decision request not found"
                ));
        if (!request.getTgChatId().equals(tgChatId)) {
            throw new IllegalArgumentException(
                    "Curator decision request belongs to another chat"
            );
        }
        return request;
    }

    private DecisionView toView(CuratorDecisionRequest request) {
        return new DecisionView(
                request.getRequestId(),
                request.getTgChatId(),
                request.getStudentQuestion(),
                request.getCurrentAnswer(),
                request.getCreditsToCharge(),
                request.getRevision(),
                request.getApprovalMessageId(),
                request.getEditPromptMessageId()
        );
    }

    private String serialize(CuratorDecisionEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize curator decision", exception);
        }
    }

    public record DecisionView(
            UUID requestId,
            Long tgChatId,
            String studentQuestion,
            String currentAnswer,
            java.math.BigDecimal creditsToCharge,
            int revision,
            Integer approvalMessageId,
            Integer editPromptMessageId
    ) {
    }
}
