package ru.itmo.nemat.orchestrator.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.orchestrator.config.KafkaDeadLetterProperties;
import ru.itmo.nemat.orchestrator.metrics.KafkaDeadLetterMetrics;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetter;
import ru.itmo.nemat.orchestrator.repository.KafkaDeadLetterRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaDeadLetterRetryPublisher {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final KafkaDeadLetterRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaDeadLetterProperties properties;
    private final KafkaDeadLetterMetrics metrics;

    @Scheduled(fixedDelayString = "${app.dead-letter.poll-interval-ms:5000}")
    @Transactional
    public void retryReadyDeadLetters() {
        List<KafkaDeadLetter> deadLetters = repository.findReadyForRetry(
                Instant.now(),
                properties.getBatchSize()
        );
        for (KafkaDeadLetter deadLetter : deadLetters) {
            retry(deadLetter);
        }
    }

    private void retry(KafkaDeadLetter deadLetter) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    deadLetter.getOriginalTopic(),
                    deadLetter.getEventKey(),
                    deadLetter.getPayload()
            );
            addHeader(record, "requestId", deadLetter.getRequestId());
            addHeader(record, "eventId", deadLetter.getEventId());
            addHeader(record, "configVersion", deadLetter.getConfigVersion());
            addHeader(
                    record,
                    KafkaDeadLetterService.RETRY_ATTEMPT_HEADER,
                    Integer.toString(deadLetter.getRetryAttempt() + 1)
            );

            kafkaTemplate.send(record).get(
                    properties.getPublishTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            deadLetter.markRetried(Instant.now());
            metrics.recordReplaySuccess();
            log.warn(
                    "Replayed dead letter {} to {}, DLT retry attempt {}",
                    deadLetter.getId(),
                    deadLetter.getOriginalTopic(),
                    deadLetter.getRetryAttempt() + 1
            );
        } catch (Exception exception) {
            Duration delay = retryDelay(deadLetter.getRetryAttempt());
            deadLetter.markPublishFailed(
                    normalizeError(exception),
                    Instant.now().plus(delay)
            );
            metrics.recordReplayFailure();
            log.error(
                    "Failed to replay dead letter {} to {}, retrying publisher in {}",
                    deadLetter.getId(),
                    deadLetter.getOriginalTopic(),
                    delay,
                    exception
            );
        }
    }

    private void addHeader(
            ProducerRecord<String, String> record,
            String name,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private Duration retryDelay(int attempts) {
        int exponent = Math.min(attempts, 16);
        long multiplier = 1L << exponent;
        long delayMillis = Math.min(
                properties.getRetryMaxDelay().toMillis(),
                properties.getRetryBaseDelay().toMillis() * multiplier
        );
        return Duration.ofMillis(delayMillis);
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
