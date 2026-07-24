package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.model.CuratorIntakeOutboxEvent;
import ru.itmo.nemat.tgconnector.repository.CuratorIntakeOutboxRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class CuratorIntakeOutboxPublisher {

    private static final String TOPIC = "curator-intake-decisions";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final CuratorIntakeOutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.curator-intake-outbox.batch-size:50}")
    private int batchSize;

    @Value("${app.curator-intake-outbox.publish-timeout-seconds:10}")
    private long publishTimeoutSeconds;

    @Value("${app.curator-intake-outbox.retry-base-delay-ms:1000}")
    private long retryBaseDelayMs;

    @Value("${app.curator-intake-outbox.retry-max-delay-ms:60000}")
    private long retryMaxDelayMs;

    @Value("${app.curator-intake-outbox.retention-days:7}")
    private long retentionDays;

    @Scheduled(fixedDelayString = "${app.curator-intake-outbox.poll-interval-ms:250}")
    @Transactional
    public void publishReadyEvents() {
        List<CuratorIntakeOutboxEvent> events =
                repository.findReadyForPublishing(Instant.now(), batchSize);
        for (CuratorIntakeOutboxEvent event : events) {
            publish(event);
        }
    }

    @Scheduled(cron = "${app.curator-intake-outbox.cleanup-cron:0 50 3 * * *}")
    @Transactional
    public void deletePublishedEvents() {
        repository.deletePublishedBefore(
                Instant.now().minus(retentionDays, ChronoUnit.DAYS)
        );
    }

    private void publish(CuratorIntakeOutboxEvent event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    event.getRequestId().toString(),
                    event.getPayload()
            );
            record.headers().add(
                    "eventId",
                    event.getEventId().toString().getBytes(StandardCharsets.UTF_8)
            );
            record.headers().add(
                    "requestId",
                    event.getRequestId().toString().getBytes(StandardCharsets.UTF_8)
            );
            kafkaTemplate.send(record).get(publishTimeoutSeconds, TimeUnit.SECONDS);
            event.markPublished(Instant.now());
        } catch (Exception exception) {
            Duration delay = retryDelay(event.getAttempts());
            event.recordFailure(normalizeError(exception), Instant.now().plus(delay));
            log.warn(
                    "[{}] Curator intake decision delivery failed, retry {} in {} ms",
                    event.getRequestId(),
                    event.getAttempts(),
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
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }
}
