package ru.itmo.nemat.orchestrator.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.orchestrator.config.KafkaDeadLetterProperties;
import ru.itmo.nemat.orchestrator.dto.CuratorSystemNotificationCommand;
import ru.itmo.nemat.orchestrator.dto.SendVkMessageCommand;
import ru.itmo.nemat.orchestrator.dto.VkMessageDeliveryResultEvent;
import ru.itmo.nemat.orchestrator.metrics.KafkaDeadLetterMetrics;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetter;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetterStatus;
import ru.itmo.nemat.orchestrator.model.WorkflowState;
import ru.itmo.nemat.orchestrator.producer.CuratorSystemNotificationProducer;
import ru.itmo.nemat.orchestrator.repository.KafkaDeadLetterRepository;
import ru.itmo.nemat.orchestrator.repository.OutboxEventRepository;
import ru.itmo.nemat.orchestrator.repository.WorkflowStateRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaDeadLetterService {

    public static final String RETRY_ATTEMPT_HEADER = "dltRetryAttempt";
    private static final String VK_OUTGOING_TOPIC = "vk-outgoing-messages";

    private final KafkaDeadLetterRepository repository;
    private final WorkflowStateRepository workflowRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxService outboxService;
    private final CuratorSystemNotificationProducer notificationProducer;
    private final KafkaDeadLetterProperties properties;
    private final ObjectMapper objectMapper;
    private final KafkaDeadLetterMetrics metrics;

    @Transactional
    public void store(ConsumerRecord<String, String> record) {
        if (repository.existsByDltTopicAndDltPartitionAndDltOffset(
                record.topic(),
                record.partition(),
                record.offset()
        )) {
            metrics.recordDuplicate();
            return;
        }

        int retryAttempt = nonNegativeIntHeader(record, RETRY_ATTEMPT_HEADER);
        boolean exhausted = retryAttempt >= properties.getMaxRetryAttempts();
        Instant now = Instant.now();
        KafkaDeadLetter deadLetter = KafkaDeadLetter.builder()
                .id(UUID.randomUUID())
                .dltTopic(record.topic())
                .dltPartition(record.partition())
                .dltOffset(record.offset())
                .originalTopic(originalTopic(record.topic()))
                .eventKey(record.key())
                .payload(record.value())
                .requestId(stringHeader(record, "requestId"))
                .eventId(stringHeader(record, "eventId"))
                .configVersion(stringHeader(record, "configVersion"))
                .exceptionClass(stringHeader(record, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .exceptionMessage(stringHeader(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .exceptionStacktrace(stringHeader(record, KafkaHeaders.DLT_EXCEPTION_STACKTRACE))
                .retryAttempt(retryAttempt)
                .status(exhausted
                        ? KafkaDeadLetterStatus.EXHAUSTED
                        : KafkaDeadLetterStatus.PENDING)
                .receivedAt(now)
                .nextRetryAt(exhausted
                        ? null
                        : now.plus(properties.getInitialRetryDelay()))
                .build();
        repository.save(deadLetter);

        if (exhausted) {
            finalizeExhausted(deadLetter);
        }
        metrics.recordReceived(deadLetter.getStatus());
        log.error(
                "Stored Kafka dead letter from {} at retry attempt {}, status {}",
                deadLetter.getOriginalTopic(),
                retryAttempt,
                deadLetter.getStatus()
        );
    }

    private void finalizeExhausted(KafkaDeadLetter deadLetter) {
        UUID requestId = parseUuid(deadLetter.getRequestId());
        if (requestId == null) {
            return;
        }

        if (VK_OUTGOING_TOPIC.equals(deadLetter.getOriginalTopic())) {
            String deduplicationKey = requestId + ":VK_DLT_EXHAUSTED";
            if (!outboxRepository.existsByDeduplicationKey(deduplicationKey)) {
                outboxService.enqueue(
                        requestId,
                        deduplicationKey,
                        "vk-message-delivery-results",
                        requestId.toString(),
                        new VkMessageDeliveryResultEvent(
                                requestId,
                                false,
                                null,
                                "VK delivery retries and DLT retries exhausted",
                                deliveryAttempt(deadLetter)
                        )
                );
            }
            deadLetter.markNotified(Instant.now());
            return;
        }

        if ("curator-system-notifications".equals(deadLetter.getOriginalTopic())) {
            return;
        }

        WorkflowState state = workflowRepository.findById(requestId).orElse(null);
        if (state == null) {
            return;
        }

        String type = "DLT_EXHAUSTED_" + sanitize(deadLetter.getOriginalTopic());
        String deduplicationKey = requestId + ":CURATOR_NOTIFICATION:" + type;
        if (!outboxRepository.existsByDeduplicationKey(deduplicationKey)) {
            notificationProducer.send(new CuratorSystemNotificationCommand(
                    requestId,
                    state.getVkGroupId(),
                    type,
                    state.getStatus().name(),
                    "Kafka не смогла обработать событие "
                            + deadLetter.getOriginalTopic(),
                    null
            ));
        }
        deadLetter.markNotified(Instant.now());
    }

    private int deliveryAttempt(KafkaDeadLetter deadLetter) {
        try {
            SendVkMessageCommand command = objectMapper.readValue(
                    deadLetter.getPayload(),
                    SendVkMessageCommand.class
            );
            return Math.max(1, command.deliveryAttempt());
        } catch (Exception exception) {
            log.warn(
                    "Failed to read delivery attempt from dead letter {}",
                    deadLetter.getId(),
                    exception
            );
            return 1;
        }
    }

    private String originalTopic(String dltTopic) {
        return dltTopic.endsWith(".DLT")
                ? dltTopic.substring(0, dltTopic.length() - 4)
                : dltTopic;
    }

    private int nonNegativeIntHeader(
            ConsumerRecord<String, String> record,
            String name
    ) {
        String value = stringHeader(record, name);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String stringHeader(
            ConsumerRecord<String, String> record,
            String name
    ) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String sanitize(String topic) {
        return topic.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
    }
}
