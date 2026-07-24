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
import ru.itmo.nemat.tgconnector.bot.CuratorTelegramBot;
import ru.itmo.nemat.tgconnector.dto.BillingRefundResultEvent;
import ru.itmo.nemat.shared.kafka.KafkaCorrelationHeaders;
import ru.itmo.nemat.tgconnector.model.BillingRefund;
import ru.itmo.nemat.tgconnector.model.BillingRefundStatus;
import ru.itmo.nemat.tgconnector.repository.BillingRefundRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class BillingRefundResultPublisher {

    private static final String TOPIC = "billing-refund-results";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final BillingRefundRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CuratorTelegramBot curatorTelegramBot;

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
        List<BillingRefund> refunds = repository.findReadyResults(Instant.now(), batchSize);
        for (BillingRefund refund : refunds) {
            publish(refund);
        }
    }

    private void publish(BillingRefund refund) {
        try {
            BillingRefundResultEvent event = toResult(refund);
            String payload = objectMapper.writeValueAsString(event);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    event.requestId().toString(),
                    payload
            );
            KafkaCorrelationHeaders.addRequestId(record, event.requestId());
            kafkaTemplate.send(record).get(timeoutSeconds, TimeUnit.SECONDS);
            refund.markResultPublished(Instant.now());
            notifyCurator(refund);
            log.info("[{}] Billing refund result delivered to {}", refund.getRequestId(), TOPIC);
        } catch (Exception exception) {
            Duration retryDelay = calculateRetryDelay(refund.getPublishAttempts());
            refund.recordPublishFailure(
                    normalizeError(exception),
                    Instant.now().plus(retryDelay)
            );
            log.warn(
                    "[{}] Billing refund result delivery failed, retry {} in {} ms",
                    refund.getRequestId(),
                    refund.getPublishAttempts(),
                    retryDelay.toMillis(),
                    exception
            );
        }
    }

    private BillingRefundResultEvent toResult(BillingRefund refund) {
        return new BillingRefundResultEvent(
                refund.getRequestId(),
                refund.getStatus().name(),
                refund.getCredits(),
                refund.getBalanceAfter(),
                refund.getErrorMessage()
        );
    }

    private void notifyCurator(BillingRefund refund) {
        if (refund.getStatus() == BillingRefundStatus.REFUNDED
                && refund.getCuratorId() != null) {
            curatorTelegramBot.sendRefundNotice(
                    refund.getCuratorId(),
                    refund.getCredits(),
                    refund.getBalanceAfter()
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
