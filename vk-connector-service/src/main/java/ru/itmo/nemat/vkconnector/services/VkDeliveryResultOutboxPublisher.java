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
import ru.itmo.nemat.shared.kafka.KafkaCorrelationHeaders;
import ru.itmo.nemat.vkconnector.dto.VkMessageDeliveryResultEvent;
import ru.itmo.nemat.vkconnector.model.VkOutgoingDelivery;
import ru.itmo.nemat.vkconnector.model.VkOutgoingDeliveryStatus;
import ru.itmo.nemat.vkconnector.repository.VkOutgoingDeliveryRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class VkDeliveryResultOutboxPublisher {

    private static final String TOPIC = "vk-message-delivery-results";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final VkOutgoingDeliveryRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.vk-delivery-outbox.batch-size:50}")
    private int batchSize;

    @Value("${app.vk-delivery-outbox.publish-timeout-seconds:10}")
    private long publishTimeoutSeconds;

    @Value("${app.vk-delivery-outbox.retry-base-delay-ms:1000}")
    private long retryBaseDelayMs;

    @Value("${app.vk-delivery-outbox.retry-max-delay-ms:60000}")
    private long retryMaxDelayMs;

    @Value("${app.vk-delivery-outbox.retention-days:7}")
    private long retentionDays;

    @Scheduled(fixedDelayString = "${app.vk-delivery-outbox.poll-interval-ms:250}")
    @Transactional
    public void publishReadyResults() {
        List<VkOutgoingDelivery> deliveries =
                repository.findReadyForPublishing(Instant.now(), batchSize);
        for (VkOutgoingDelivery delivery : deliveries) {
            publish(delivery);
        }
    }

    @Scheduled(cron = "${app.vk-delivery-outbox.cleanup-cron:0 35 3 * * *}")
    @Transactional
    public void deletePublishedResults() {
        repository.deletePublishedBefore(
                Instant.now().minus(retentionDays, ChronoUnit.DAYS)
        );
    }

    private void publish(VkOutgoingDelivery delivery) {
        try {
            VkMessageDeliveryResultEvent event = new VkMessageDeliveryResultEvent(
                    delivery.getRequestId(),
                    delivery.getStatus() == VkOutgoingDeliveryStatus.SUCCEEDED,
                    delivery.getVkMessageId(),
                    delivery.getDeliveryError(),
                    delivery.getDeliveryAttempt()
            );
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    delivery.getRequestId().toString(),
                    objectMapper.writeValueAsString(event)
            );
            KafkaCorrelationHeaders.addRequestId(record, delivery.getRequestId());
            kafkaTemplate.send(record).get(publishTimeoutSeconds, TimeUnit.SECONDS);
            delivery.markResultPublished(Instant.now());
        } catch (Exception exception) {
            Duration delay = retryDelay(delivery.getPublishAttempts());
            delivery.recordPublishFailure(
                    normalizeError(exception),
                    Instant.now().plus(delay)
            );
            log.warn(
                    "[{}] VK delivery result publication failed, retry {} in {} ms",
                    delivery.getRequestId(),
                    delivery.getPublishAttempts(),
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
