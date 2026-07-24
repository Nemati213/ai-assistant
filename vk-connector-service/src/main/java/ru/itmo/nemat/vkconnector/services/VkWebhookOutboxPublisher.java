package ru.itmo.nemat.vkconnector.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.shared.kafka.KafkaCorrelationHeaders;
import ru.itmo.nemat.vkconnector.model.VkWebhookOutboxEvent;
import ru.itmo.nemat.vkconnector.repository.VkWebhookOutboxRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class VkWebhookOutboxPublisher {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final VkWebhookOutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.vk-webhook-outbox.batch-size:100}")
    private int batchSize;

    @Value("${app.vk-webhook-outbox.publish-timeout-seconds:10}")
    private long publishTimeoutSeconds;

    @Value("${app.vk-webhook-outbox.retry-base-delay-ms:1000}")
    private long retryBaseDelayMs;

    @Value("${app.vk-webhook-outbox.retry-max-delay-ms:60000}")
    private long retryMaxDelayMs;

    @Value("${app.vk-webhook-outbox.retention-days:7}")
    private long retentionDays;

    @Scheduled(fixedDelayString = "${app.vk-webhook-outbox.poll-interval-ms:250}")
    @Transactional
    public void publishReadyEvents() {
        List<VkWebhookOutboxEvent> events =
                repository.findReadyForPublishing(Instant.now(), batchSize);
        for (VkWebhookOutboxEvent event : events) {
            publish(event);
        }
    }

    @Scheduled(cron = "${app.vk-webhook-outbox.cleanup-cron:0 40 3 * * *}")
    @Transactional
    public void deletePublishedEvents() {
        repository.deletePublishedBefore(
                Instant.now().minus(retentionDays, ChronoUnit.DAYS)
        );
    }

    private void publish(VkWebhookOutboxEvent event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    event.getTopic(),
                    event.getEventKey(),
                    event.getPayload()
            );
            KafkaCorrelationHeaders.addRequestId(record, event.getRequestId());
            kafkaTemplate.send(record).get(publishTimeoutSeconds, TimeUnit.SECONDS);
            event.markPublished(Instant.now());
        } catch (Exception exception) {
            Duration delay = retryDelay(event.getAttempts());
            event.recordFailure(
                    normalizeError(exception),
                    Instant.now().plus(delay)
            );
            log.warn(
                    "[{}] VK webhook outbox delivery to {} failed, retry {} in {} ms",
                    event.getRequestId(),
                    event.getTopic(),
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
