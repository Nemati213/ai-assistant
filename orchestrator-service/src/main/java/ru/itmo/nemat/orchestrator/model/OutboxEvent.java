package ru.itmo.nemat.orchestrator.model;

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
@Table(name = "outbox_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "deduplication_key", nullable = false, unique = true)
    private String deduplicationKey;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "event_key", nullable = false)
    private String eventKey;

    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
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

    public void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    public void recordFailure(String error, Instant nextAttemptAt) {
        this.attempts++;
        this.lastError = error;
        this.nextAttemptAt = nextAttemptAt;
    }
}
