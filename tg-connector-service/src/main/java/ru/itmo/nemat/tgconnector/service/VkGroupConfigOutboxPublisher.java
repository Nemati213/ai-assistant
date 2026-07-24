package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.model.VkGroupConfigOutboxEvent;
import ru.itmo.nemat.tgconnector.repository.VkGroupConfigOutboxRepository;
import ru.itmo.nemat.shared.kafka.KafkaCorrelationHeaders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class VkGroupConfigOutboxPublisher {

    private static final String TOPIC = "vk-group-configs";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final VkGroupConfigOutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.vk-config-outbox.batch-size:50}")
    private int batchSize;

    @Value("${app.vk-config-outbox.publish-timeout-seconds:10}")
    private long publishTimeoutSeconds;

    @Value("${app.vk-config-outbox.retry-base-delay-ms:1000}")
    private long retryBaseDelayMs;

    @Value("${app.vk-config-outbox.retry-max-delay-ms:60000}")
    private long retryMaxDelayMs;

    @Value("${app.vk-config-outbox.retention-days:7}")
    private long retentionDays;

    @Scheduled(fixedDelayString = "${app.vk-config-outbox.poll-interval-ms:250}")
    @Transactional
    public void publishReadyEvents() {
        List<VkGroupConfigOutboxEvent> events =
                repository.findReadyForPublishing(Instant.now(), batchSize);
        for (VkGroupConfigOutboxEvent event : events) {
            publish(event);
        }
    }

    @Scheduled(cron = "${app.vk-config-outbox.cleanup-cron:0 15 3 * * *}")
    @Transactional
    public void deletePublishedEvents() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        repository.deletePublishedBefore(cutoff);
    }

    private void publish(VkGroupConfigOutboxEvent event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    event.getVkGroupId(),
                    event.getPayload()
            );
            record.headers().add(
                    "eventId",
                    event.getEventId().toString().getBytes(StandardCharsets.UTF_8)
            );
            record.headers().add(
                    "configVersion",
                    Long.toString(event.getConfigVersion()).getBytes(StandardCharsets.UTF_8)
            );
            KafkaCorrelationHeaders.addRequestId(record, event.getEventId());
            kafkaTemplate.send(record).get(publishTimeoutSeconds, TimeUnit.SECONDS);
            event.markPublished(Instant.now());
        } catch (Exception exception) {
            Duration delay = calculateRetryDelay(event.getAttempts());
            event.recordFailure(
                    normalizeError(exception),
                    Instant.now().plus(delay)
            );
            log.warn(
                    "[{}] VK group config delivery failed, retry {} in {} ms",
                    event.getEventId(),
                    event.getAttempts(),
                    delay.toMillis(),
                    exception
            );
        }
    }

    private Duration calculateRetryDelay(int attempts) {
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
