package ru.itmo.nemat.orchestrator.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "workflow_states")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowState {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "vk_chat_id", nullable = false)
    private String vkChatId;

    @Column(name = "vk_user_id", nullable = false)
    private String vkUserId;

    @Column(name = "vk_group_id", nullable = false)
    private String vkGroupId;

    @Column(name = "student_question", columnDefinition = "TEXT", nullable = false)
    private String studentQuestion;

    @Column(name = "available_balance_after_reservation", precision = 38, scale = 2)
    private BigDecimal availableBalanceAfterReservation;

    @Column(name = "reserved_credits", precision = 38, scale = 2)
    private BigDecimal reservedCredits;

    @Column(name = "reservation_expires_at")
    private Instant reservationExpiresAt;

    @Column(name = "reservation_error", columnDefinition = "TEXT")
    private String reservationError;

    @Column(name = "ai_suggested_answer", columnDefinition = "TEXT")
    private String aiSuggestedAnswer;

    @Column(name = "ai_error", columnDefinition = "TEXT")
    private String aiError;

    @Column(name = "ai_failed_at")
    private Instant aiFailedAt;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "provider_cost_usd", precision = 20, scale = 10)
    private BigDecimal providerCostUsd;

    @Column(name = "credits_to_charge", precision = 38, scale = 2)
    private BigDecimal creditsToCharge;

    @Column(name = "credits_per_usd", precision = 38, scale = 2)
    private BigDecimal creditsPerUsd;

    @Column(name = "minimum_charge", precision = 38, scale = 2)
    private BigDecimal minimumCharge;

    @Column(name = "billing_error", columnDefinition = "TEXT")
    private String billingError;

    @Column(name = "vk_message_id")
    private Long vkMessageId;

    @Column(name = "delivery_error", columnDefinition = "TEXT")
    private String deliveryError;

    @Column(name = "refunded_credits", precision = 38, scale = 2)
    private BigDecimal refundedCredits;

    @Column(name = "refund_error", columnDefinition = "TEXT")
    private String refundError;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Builder.Default
    @Column(name = "delivery_attempt", nullable = false)
    private int deliveryAttempt = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_mode")
    private WorkflowResponseMode responseMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WorkflowStatus status;

    @Builder.Default
    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt = Instant.now();

    @Builder.Default
    @Column(name = "recovery_attempts", nullable = false)
    private int recoveryAttempts = 0;

    @Column(name = "recovery_exhausted_notified_at")
    private Instant recoveryExhaustedNotifiedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "workflow_state_photos",
            joinColumns = @JoinColumn(name = "request_id")
    )
    @Column(name = "photo_url", columnDefinition = "TEXT")
    private List<String> photoUrls;

    public void setStatus(WorkflowStatus status) {
        if (this.status != status) {
            this.status = status;
            this.statusChangedAt = Instant.now();
            this.recoveryAttempts = 0;
            this.recoveryExhaustedNotifiedAt = null;
        }
    }

    public void markRecoveryAttempt(Instant attemptedAt) {
        this.statusChangedAt = attemptedAt;
        this.recoveryAttempts++;
    }

    public void markRecoveryExhaustedNotified(Instant notifiedAt) {
        this.recoveryExhaustedNotifiedAt = notifiedAt;
    }
}
