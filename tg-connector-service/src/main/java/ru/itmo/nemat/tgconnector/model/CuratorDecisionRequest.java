package ru.itmo.nemat.tgconnector.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "curator_decision_requests")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CuratorDecisionRequest {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "tg_chat_id", nullable = false)
    private Long tgChatId;

    @Column(name = "vk_group_id", nullable = false)
    private String vkGroupId;

    @Column(name = "student_question", nullable = false, columnDefinition = "TEXT")
    private String studentQuestion;

    @Column(name = "current_answer", nullable = false, columnDefinition = "TEXT")
    private String currentAnswer;

    @Column(name = "tokens_used", nullable = false)
    private int tokensUsed;

    @Column(name = "credits_to_charge", nullable = false, precision = 38, scale = 2)
    private BigDecimal creditsToCharge;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CuratorDecisionRequestStatus status;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "approval_message_id")
    private Integer approvalMessageId;

    @Column(name = "edit_prompt_message_id")
    private Integer editPromptMessageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void markApprovalDelivered(Integer messageId, Instant now) {
        this.approvalMessageId = messageId;
        this.updatedAt = now;
    }

    public void beginEditing(Instant now) {
        this.status = CuratorDecisionRequestStatus.AWAITING_EDIT;
        this.editPromptMessageId = null;
        this.updatedAt = now;
    }

    public void attachEditPrompt(Integer messageId, Instant now) {
        this.editPromptMessageId = messageId;
        this.updatedAt = now;
    }

    public void cancelEditing(Instant now) {
        this.status = CuratorDecisionRequestStatus.AWAITING_DECISION;
        this.editPromptMessageId = null;
        this.updatedAt = now;
    }

    public void applyEditedAnswer(String answer, Integer approvalMessageId, Instant now) {
        this.currentAnswer = answer;
        this.revision++;
        this.status = CuratorDecisionRequestStatus.AWAITING_DECISION;
        this.approvalMessageId = approvalMessageId;
        this.editPromptMessageId = null;
        this.updatedAt = now;
    }

    public void queueDecision(Instant now) {
        this.status = CuratorDecisionRequestStatus.DECISION_QUEUED;
        this.editPromptMessageId = null;
        this.updatedAt = now;
    }
}
