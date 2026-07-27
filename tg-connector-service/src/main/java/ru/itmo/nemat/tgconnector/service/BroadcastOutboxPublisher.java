package ru.itmo.nemat.tgconnector.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class BroadcastOutboxPublisher {

    private static final String TOPIC = "vk-outgoing-messages";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;
    private final long publishTimeoutSeconds;
    private final long retryBaseDelayMs;
    private final long retryMaxDelayMs;
    private final long retentionDays;

    public BroadcastOutboxPublisher(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.broadcast-outbox.batch-size:25}") int batchSize,
            @Value("${app.broadcast-outbox.publish-timeout-seconds:10}")
            long publishTimeoutSeconds,
            @Value("${app.broadcast-outbox.retry-base-delay-ms:1000}")
            long retryBaseDelayMs,
            @Value("${app.broadcast-outbox.retry-max-delay-ms:60000}")
            long retryMaxDelayMs,
            @Value("${app.broadcast-outbox.retention-days:7}") long retentionDays
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.publishTimeoutSeconds = publishTimeoutSeconds;
        this.retryBaseDelayMs = retryBaseDelayMs;
        this.retryMaxDelayMs = retryMaxDelayMs;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${app.broadcast-outbox.poll-interval-ms:350}")
    @Transactional
    public void publishReadyEvents() {
        List<OutboxRow> events = jdbcTemplate.query("""
                SELECT
                    event_id,
                    request_id,
                    partition_key,
                    payload,
                    attempts
                FROM broadcast_outbox
                WHERE published_at IS NULL
                  AND next_attempt_at <= ?
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new OutboxRow(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getObject("request_id", UUID.class),
                        resultSet.getString("partition_key"),
                        resultSet.getString("payload"),
                        resultSet.getInt("attempts")
                ),
                databaseTime(Instant.now()),
                batchSize
        );
        for (OutboxRow event : events) {
            publish(event);
        }
    }

    @Scheduled(cron = "${app.broadcast-outbox.cleanup-cron:0 20 4 * * *}")
    @Transactional
    public void deletePublishedEvents() {
        jdbcTemplate.update("""
                DELETE FROM broadcast_outbox
                WHERE published_at IS NOT NULL
                  AND published_at < ?
                """,
                databaseTime(
                        Instant.now().minus(retentionDays, ChronoUnit.DAYS)
                )
        );
    }

    private void publish(OutboxRow event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    event.partitionKey(),
                    event.payload()
            );
            record.headers().add(
                    "eventId",
                    event.eventId().toString().getBytes(StandardCharsets.UTF_8)
            );
            record.headers().add(
                    "requestId",
                    event.requestId().toString().getBytes(StandardCharsets.UTF_8)
            );
            kafkaTemplate.send(record)
                    .get(publishTimeoutSeconds, TimeUnit.SECONDS);
            jdbcTemplate.update("""
                    UPDATE broadcast_outbox
                    SET published_at = ?,
                        last_error = NULL
                    WHERE event_id = ?
                    """,
                    databaseTime(Instant.now()),
                    event.eventId()
            );
        } catch (Exception exception) {
            Duration delay = retryDelay(event.attempts());
            jdbcTemplate.update("""
                    UPDATE broadcast_outbox
                    SET attempts = attempts + 1,
                        last_error = ?,
                        next_attempt_at = ?
                    WHERE event_id = ?
                    """,
                    normalizeError(exception),
                    databaseTime(Instant.now().plus(delay)),
                    event.eventId()
            );
            log.warn(
                    "[{}] Broadcast command delivery failed, retry in {} ms",
                    event.requestId(),
                    delay.toMillis(),
                    exception
            );
        }
    }

    private Duration retryDelay(int attempts) {
        int exponent = Math.min(attempts, 16);
        long multiplier = 1L << exponent;
        return Duration.ofMillis(
                Math.min(retryMaxDelayMs, retryBaseDelayMs * multiplier)
        );
    }

    private String normalizeError(Exception exception) {
        Throwable cause = exception.getCause() == null
                ? exception
                : exception.getCause();
        String message = cause.getClass().getSimpleName()
                + ": "
                + cause.getMessage();
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }

    private OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record OutboxRow(
            UUID eventId,
            UUID requestId,
            String partitionKey,
            String payload,
            int attempts
    ) {
    }
}
