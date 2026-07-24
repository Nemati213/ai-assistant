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
@Table(name = "billing_refunds")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingRefund {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "curator_id")
    private UUID curatorId;

    @Column(name = "credits", nullable = false, precision = 38, scale = 2)
    private BigDecimal credits;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BillingRefundStatus status;

    @Column(name = "balance_after", precision = 38, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "result_published_at")
    private Instant resultPublishedAt;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    @Column(name = "next_publish_attempt_at", nullable = false)
    private Instant nextPublishAttemptAt;

    @Column(name = "last_publish_error", columnDefinition = "TEXT")
    private String lastPublishError;

    public void markResultPublished(Instant publishedAt) {
        this.resultPublishedAt = publishedAt;
        this.lastPublishError = null;
    }

    public void recordPublishFailure(String error, Instant nextAttemptAt) {
        this.publishAttempts++;
        this.lastPublishError = error;
        this.nextPublishAttemptAt = nextAttemptAt;
    }
}
