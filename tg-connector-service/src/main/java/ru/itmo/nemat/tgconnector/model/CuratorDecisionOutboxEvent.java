package ru.itmo.nemat.tgconnector.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "curator_decision_outbox")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CuratorDecisionOutboxEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    public void markPublished(Instant now) {
        this.publishedAt = now;
        this.lastError = null;
    }

    public void recordFailure(String error, Instant nextAttemptAt) {
        this.attempts++;
        this.lastError = error;
        this.nextAttemptAt = nextAttemptAt;
    }
}
