package ru.itmo.nemat.aiservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.aiservice.dto.AiAnswerGeneratedEvent;
import ru.itmo.nemat.aiservice.dto.AiGenerationFailedEvent;
import ru.itmo.nemat.aiservice.model.AiGenerationRequest;
import ru.itmo.nemat.aiservice.model.AiGenerationStatus;
import ru.itmo.nemat.aiservice.repository.AiGenerationRequestRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiResultPublisher {

    private static final String SUCCESS_TOPIC = "ai-generation-results";
    private static final String FAILURE_TOPIC = "ai-generation-failures";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final AiGenerationRequestRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.publisher.batch-size:50}")
    private int batchSize;

    @Value("${app.ai.publisher.timeout-seconds:10}")
    private long timeoutSeconds;

    @Value("${app.ai.publisher.retry-base-delay-ms:1000}")
    private long retryBaseDelayMs;

    @Value("${app.ai.publisher.retry-max-delay-ms:60000}")
    private long retryMaxDelayMs;

    @Scheduled(fixedDelayString = "${app.ai.publisher.poll-interval-ms:250}")
    @Transactional
    public void publishReadyResults() {
        List<AiGenerationRequest> requests =
                repository.findReadyResults(Instant.now(), batchSize);
        for (AiGenerationRequest request : requests) {
            publish(request);
        }
    }

    private void publish(AiGenerationRequest request) {
        try {
            String topic = request.getStatus() == AiGenerationStatus.COMPLETED
                    ? SUCCESS_TOPIC
                    : FAILURE_TOPIC;
            Object event = toEvent(request);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic,
                    request.getRequestId().toString(),
                    objectMapper.writeValueAsString(event)
            );
            record.headers().add(
                    "requestId",
                    request.getRequestId().toString().getBytes(StandardCharsets.UTF_8)
            );

            kafkaTemplate.send(record).get(timeoutSeconds, TimeUnit.SECONDS);
            request.markResultPublished(Instant.now());
            log.info(
                    "[{}] Stored AI {} delivered to {}",
                    request.getRequestId(),
                    request.getStatus(),
                    topic
            );
        } catch (Exception exception) {
            Duration retryDelay = calculateRetryDelay(request.getPublishAttempts());
            request.recordPublishFailure(
                    normalizeError(exception),
                    Instant.now().plus(retryDelay)
            );
            log.warn(
                    "[{}] AI result delivery failed, retry {} in {} ms",
                    request.getRequestId(),
                    request.getPublishAttempts(),
                    retryDelay.toMillis(),
                    exception
            );
        }
    }

    private Object toEvent(AiGenerationRequest request) {
        if (request.getStatus() == AiGenerationStatus.COMPLETED) {
            return new AiAnswerGeneratedEvent(
                    request.getRequestId(),
                    request.getVkChatId(),
                    request.getVkGroupId(),
                    request.getAnswerText(),
                    request.getTokensUsed(),
                    request.getProviderCostUsd()
            );
        }
        return new AiGenerationFailedEvent(
                request.getRequestId(),
                request.getVkChatId(),
                request.getVkGroupId(),
                request.getErrorMessage(),
                request.getCompletedAt()
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
