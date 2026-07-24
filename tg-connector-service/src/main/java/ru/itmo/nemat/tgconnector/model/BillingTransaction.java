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
@Table(name = "billing_transactions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingTransaction {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "curator_id", nullable = false)
    private UUID curatorId;

    @Column(name = "vk_group_id", nullable = false)
    private String vkGroupId;

    @Column(name = "ai_tokens", nullable = false)
    private int aiTokens;

    @Column(name = "provider_cost_usd", nullable = false, precision = 20, scale = 10)
    private BigDecimal providerCostUsd;

    @Column(name = "credits_charged", nullable = false, precision = 38, scale = 2)
    private BigDecimal creditsCharged;

    @Column(name = "credits_per_usd", nullable = false, precision = 38, scale = 2)
    private BigDecimal creditsPerUsd;

    @Column(name = "minimum_charge", nullable = false, precision = 38, scale = 2)
    private BigDecimal minimumCharge;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BillingStatus status;

    @Column(name = "balance_after", nullable = false)
    private BigDecimal balanceAfter;

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
