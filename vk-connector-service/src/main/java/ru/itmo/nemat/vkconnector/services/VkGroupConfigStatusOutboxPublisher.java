package ru.itmo.nemat.vkconnector.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.vkconnector.dto.VkGroupConfigStatusEvent;
import ru.itmo.nemat.vkconnector.model.VkGroupConfigStatusOutboxEvent;
import ru.itmo.nemat.vkconnector.repository.VkGroupConfigStatusOutboxRepository;
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
public class VkGroupConfigStatusOutboxPublisher {

    private static final String TOPIC = "vk-group-config-status";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final VkGroupConfigStatusOutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.vk-status-outbox.batch-size:50}")
    private int batchSize;

    @Value("${app.vk-status-outbox.publish-timeout-seconds:10}")
    private long publishTimeoutSeconds;

    @Value("${app.vk-status-outbox.retry-base-delay-ms:1000}")
    private long retryBaseDelayMs;

    @Value("${app.vk-status-outbox.retry-max-delay-ms:60000}")
    private long retryMaxDelayMs;

    @Value("${app.vk-status-outbox.retention-days:7}")
    private long retentionDays;

    @Scheduled(fixedDelayString = "${app.vk-status-outbox.poll-interval-ms:250}")
    @Transactional
    public void publishReadyEvents() {
        List<VkGroupConfigStatusOutboxEvent> events =
                repository.findReadyForPublishing(Instant.now(), batchSize);
        for (VkGroupConfigStatusOutboxEvent event : events) {
            publish(event);
        }
    }

    @Scheduled(cron = "${app.vk-status-outbox.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void deletePublishedEvents() {
        repository.deletePublishedBefore(
                Instant.now().minus(retentionDays, ChronoUnit.DAYS)
        );
    }

    private void publish(VkGroupConfigStatusOutboxEvent event) {
        try {
            VkGroupConfigStatusEvent payload = new VkGroupConfigStatusEvent(
                    event.getEventId(),
                    event.getConfigVersion(),
                    event.getVkGroupId(),
                    event.getStatus(),
                    event.getErrorMessage()
            );
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    event.getVkGroupId(),
                    objectMapper.writeValueAsString(payload)
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
            event.recordFailure(normalizeError(exception), Instant.now().plus(delay));
            log.warn(
                    "[{}] VK config status delivery failed, retry {} in {} ms",
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
