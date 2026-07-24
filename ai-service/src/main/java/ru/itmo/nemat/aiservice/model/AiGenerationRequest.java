package ru.itmo.nemat.aiservice.model;

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
@Table(name = "ai_generation_requests")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiGenerationRequest {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "command_fingerprint", nullable = false, length = 64)
    private String commandFingerprint;

    @Column(name = "vk_chat_id", nullable = false)
    private String vkChatId;

    @Column(name = "vk_group_id", nullable = false)
    private String vkGroupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AiGenerationStatus status;

    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "provider_cost_usd", precision = 20, scale = 10)
    private BigDecimal providerCostUsd;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "result_published_at")
    private Instant resultPublishedAt;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    @Column(name = "next_publish_attempt_at", nullable = false)
    private Instant nextPublishAttemptAt;

    @Column(name = "last_publish_error", columnDefinition = "TEXT")
    private String lastPublishError;

    public void complete(
            String answerText,
            int tokensUsed,
            BigDecimal providerCostUsd,
            Instant completedAt
    ) {
        this.answerText = answerText;
        this.tokensUsed = tokensUsed;
        this.providerCostUsd = providerCostUsd;
        this.errorMessage = null;
        this.completedAt = completedAt;
        this.nextPublishAttemptAt = completedAt;
        this.status = AiGenerationStatus.COMPLETED;
    }

    public void fail(String errorMessage, Instant completedAt) {
        this.answerText = null;
        this.tokensUsed = null;
        this.providerCostUsd = null;
        this.errorMessage = errorMessage;
        this.completedAt = completedAt;
        this.nextPublishAttemptAt = completedAt;
        this.status = AiGenerationStatus.FAILED;
    }

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
