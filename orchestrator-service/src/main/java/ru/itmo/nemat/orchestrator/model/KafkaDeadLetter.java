package ru.itmo.nemat.orchestrator.model;

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
@Table(name = "kafka_dead_letters")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KafkaDeadLetter {

    @Id
    private UUID id;

    @Column(name = "dlt_topic", nullable = false)
    private String dltTopic;

    @Column(name = "dlt_partition", nullable = false)
    private int dltPartition;

    @Column(name = "dlt_offset", nullable = false)
    private long dltOffset;

    @Column(name = "original_topic", nullable = false)
    private String originalTopic;

    @Column(name = "event_key", columnDefinition = "TEXT")
    private String eventKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "config_version")
    private String configVersion;

    @Column(name = "exception_class", columnDefinition = "TEXT")
    private String exceptionClass;

    @Column(name = "exception_message", columnDefinition = "TEXT")
    private String exceptionMessage;

    @Column(name = "exception_stacktrace", columnDefinition = "TEXT")
    private String exceptionStacktrace;

    @Column(name = "retry_attempt", nullable = false)
    private int retryAttempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private KafkaDeadLetterStatus status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "retried_at")
    private Instant retriedAt;

    @Column(name = "last_retry_error", columnDefinition = "TEXT")
    private String lastRetryError;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    public void markRetried(Instant now) {
        this.status = KafkaDeadLetterStatus.RETRIED;
        this.retriedAt = now;
        this.nextRetryAt = null;
        this.lastRetryError = null;
    }

    public void markPublishFailed(String error, Instant nextRetryAt) {
        this.status = KafkaDeadLetterStatus.PUBLISH_FAILED;
        this.lastRetryError = error;
        this.nextRetryAt = nextRetryAt;
    }

    public void markNotified(Instant now) {
        this.notifiedAt = now;
    }
}
