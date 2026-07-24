package ru.itmo.nemat.tgconnector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.dto.BillingResultEvent;
import ru.itmo.nemat.shared.kafka.KafkaCorrelationHeaders;
import ru.itmo.nemat.tgconnector.model.BillingStatus;
import ru.itmo.nemat.tgconnector.model.BillingTransaction;
import ru.itmo.nemat.tgconnector.repository.BillingTransactionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class BillingResultPublisher {

    private static final String TOPIC = "billing-results";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final BillingTransactionRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.billing.publisher.batch-size:50}")
    private int batchSize;

    @Value("${app.billing.publisher.timeout-seconds:10}")
    private long timeoutSeconds;

    @Value("${app.billing.publisher.retry-base-delay-ms:1000}")
    private long retryBaseDelayMs;

    @Value("${app.billing.publisher.retry-max-delay-ms:60000}")
    private long retryMaxDelayMs;

    @Scheduled(fixedDelayString = "${app.billing.publisher.poll-interval-ms:250}")
    @Transactional
    public void publishReadyResults() {
        List<BillingTransaction> transactions =
                repository.findReadyResults(Instant.now(), batchSize);
        for (BillingTransaction transaction : transactions) {
            publish(transaction);
        }
    }

    private void publish(BillingTransaction transaction) {
        try {
            BillingResultEvent event = toResult(transaction);
            String payload = objectMapper.writeValueAsString(event);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    event.requestId().toString(),
                    payload
            );
            KafkaCorrelationHeaders.addRequestId(record, event.requestId());
            kafkaTemplate.send(record).get(timeoutSeconds, TimeUnit.SECONDS);
            transaction.markResultPublished(Instant.now());
            log.info("[{}] Billing result delivered to topic {}", event.requestId(), TOPIC);
        } catch (Exception exception) {
            Duration retryDelay = calculateRetryDelay(transaction.getPublishAttempts());
            transaction.recordPublishFailure(
                    normalizeError(exception),
                    Instant.now().plus(retryDelay)
            );
            log.warn(
                    "[{}] Billing result delivery failed, retry {} in {} ms",
                    transaction.getRequestId(),
                    transaction.getPublishAttempts(),
                    retryDelay.toMillis(),
                    exception
            );
        }
    }

    private BillingResultEvent toResult(BillingTransaction transaction) {
        boolean charged = transaction.getStatus() == BillingStatus.CHARGED;
        return new BillingResultEvent(
                transaction.getRequestId(),
                transaction.getStatus().name(),
                charged ? transaction.getCreditsCharged() : java.math.BigDecimal.ZERO,
                transaction.getBalanceAfter(),
                transaction.getErrorMessage()
        );
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
