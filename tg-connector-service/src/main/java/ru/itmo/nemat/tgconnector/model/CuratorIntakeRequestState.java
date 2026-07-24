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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "curator_intake_requests")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CuratorIntakeRequestState {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "tg_chat_id", nullable = false)
    private Long tgChatId;

    @Column(name = "vk_group_id", nullable = false)
    private String vkGroupId;

    @Column(name = "student_question", nullable = false, columnDefinition = "TEXT")
    private String studentQuestion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CuratorIntakeStatus status;

    @Column(name = "intake_message_id")
    private Integer intakeMessageId;

    @Column(name = "manual_prompt_message_id")
    private Integer manualPromptMessageId;

    @Builder.Default
    @Column(name = "delivery_attempt", nullable = false)
    private int deliveryAttempt = 0;

    @Column(name = "last_delivery_error", columnDefinition = "TEXT")
    private String lastDeliveryError;

    @Column(name = "failure_message_id")
    private Integer failureMessageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void markDelivered(Integer messageId, Instant now) {
        this.intakeMessageId = messageId;
        this.updatedAt = now;
    }

    public void beginManualReply(Instant now) {
        this.status = CuratorIntakeStatus.AWAITING_MANUAL_REPLY;
        this.manualPromptMessageId = null;
        this.updatedAt = now;
    }

    public void attachManualPrompt(Integer messageId, Instant now) {
        this.manualPromptMessageId = messageId;
        this.updatedAt = now;
    }

    public void cancelManualReply(Instant now) {
        this.status = CuratorIntakeStatus.AWAITING_ACTION;
        this.manualPromptMessageId = null;
        this.updatedAt = now;
    }

    public void reopenAfterManualCancellation(Instant now) {
        this.status = CuratorIntakeStatus.AWAITING_ACTION;
        this.intakeMessageId = null;
        this.manualPromptMessageId = null;
        this.updatedAt = now;
    }

    public void queueDecision(Instant now) {
        this.status = CuratorIntakeStatus.DECISION_QUEUED;
        this.manualPromptMessageId = null;
        this.updatedAt = now;
    }

    public void markManualDeliveryFailed(
            int deliveryAttempt,
            String error,
            Instant now
    ) {
        this.status = CuratorIntakeStatus.MANUAL_DELIVERY_FAILED;
        this.deliveryAttempt = deliveryAttempt;
        this.lastDeliveryError = error;
        this.failureMessageId = null;
        this.updatedAt = now;
    }

    public void markFailureDelivered(Integer messageId, Instant now) {
        this.failureMessageId = messageId;
        this.updatedAt = now;
    }

    public void queueRecoveryAction(boolean cancelled, Instant now) {
        this.status = cancelled
                ? CuratorIntakeStatus.CANCELLED
                : CuratorIntakeStatus.RECOVERY_ACTION_QUEUED;
        this.failureMessageId = null;
        this.updatedAt = now;
    }

    public void markCompleted(int deliveryAttempt, Instant now) {
        this.status = CuratorIntakeStatus.COMPLETED;
        this.deliveryAttempt = deliveryAttempt;
        this.lastDeliveryError = null;
        this.failureMessageId = null;
        this.updatedAt = now;
    }
}
