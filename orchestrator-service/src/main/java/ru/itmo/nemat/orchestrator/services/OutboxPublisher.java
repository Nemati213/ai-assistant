package ru.itmo.nemat.orchestrator.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.orchestrator.model.OutboxEvent;
import ru.itmo.nemat.orchestrator.repository.OutboxEventRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.outbox.batch-size:50}")
    private int batchSize;

    @Value("${app.outbox.publish-timeout-seconds:10}")
    private long publishTimeoutSeconds;

    @Value("${app.outbox.retry-base-delay-ms:1000}")
    private long retryBaseDelayMs;

    @Value("${app.outbox.retry-max-delay-ms:60000}")
    private long retryMaxDelayMs;

    @Value("${app.outbox.retention-days:7}")
    private long retentionDays;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:250}")
    @Transactional
    public void publishReadyEvents() {
        Instant now = Instant.now();
        List<OutboxEvent> events = repository.findReadyForPublishing(now, batchSize);

        for (OutboxEvent event : events) {
            publish(event);
        }
    }

    @Scheduled(cron = "${app.outbox.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void deletePublishedEvents() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = repository.deletePublishedBefore(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} published outbox events older than {}", deleted, cutoff);
        }
    }

    private void publish(OutboxEvent event) {
        try {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(event.getTopic(), event.getEventKey(), event.getPayload());
            record.headers().add(
                    "requestId",
                    event.getAggregateId().toString().getBytes(StandardCharsets.UTF_8)
            );

            kafkaTemplate.send(record).get(publishTimeoutSeconds, TimeUnit.SECONDS);
            event.markPublished(Instant.now());
            log.info(
                    "[{}] Outbox event {} delivered to topic {}",
                    event.getAggregateId(),
                    event.getId(),
                    event.getTopic()
            );
        } catch (Exception e) {
            Duration retryDelay = calculateRetryDelay(event.getAttempts());
            event.recordFailure(normalizeError(e), Instant.now().plus(retryDelay));
            log.warn(
                    "[{}] Outbox event {} delivery failed, retry {} in {} ms",
                    event.getAggregateId(),
                    event.getId(),
                    event.getAttempts(),
                    retryDelay.toMillis(),
                    e
            );
        }
    }

    private Duration calculateRetryDelay(int attempts) {
        int exponent = Math.min(attempts, 16);
        long multiplier = 1L << exponent;
        long delay = Math.min(retryMaxDelayMs, retryBaseDelayMs * multiplier);
        return Duration.ofMillis(delay);
    }

    private String normalizeError(Exception exception) {
        Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }
}
