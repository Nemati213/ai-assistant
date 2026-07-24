package ru.itmo.nemat.orchestrator.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.orchestrator.config.KafkaDeadLetterProperties;
import ru.itmo.nemat.orchestrator.dto.VkMessageDeliveryResultEvent;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetter;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetterStatus;
import ru.itmo.nemat.orchestrator.model.WorkflowState;
import ru.itmo.nemat.orchestrator.model.WorkflowStatus;
import ru.itmo.nemat.orchestrator.producer.CuratorSystemNotificationProducer;
import ru.itmo.nemat.orchestrator.repository.KafkaDeadLetterRepository;
import ru.itmo.nemat.orchestrator.repository.OutboxEventRepository;
import ru.itmo.nemat.orchestrator.repository.WorkflowStateRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaDeadLetterServiceTest {

    @Mock
    private KafkaDeadLetterRepository repository;
    @Mock
    private WorkflowStateRepository workflowRepository;
    @Mock
    private OutboxEventRepository outboxRepository;
    @Mock
    private OutboxService outboxService;
    @Mock
    private CuratorSystemNotificationProducer notificationProducer;

    private KafkaDeadLetterService service;
    private KafkaDeadLetterProperties properties;

    @BeforeEach
    void setUp() {
        properties = new KafkaDeadLetterProperties();
        properties.setMaxRetryAttempts(3);
        properties.setInitialRetryDelay(Duration.ofMinutes(1));
        service = new KafkaDeadLetterService(
                repository,
                workflowRepository,
                outboxRepository,
                outboxService,
                notificationProducer,
                properties,
                new ObjectMapper()
        );
    }

    @Test
    void storesFirstDeadLetterForDelayedRetry() {
        UUID requestId = UUID.randomUUID();
        ConsumerRecord<String, String> record = record(
                "ai-generation-commands.DLT",
                requestId,
                null
        );

        service.store(record);

        ArgumentCaptor<KafkaDeadLetter> captor =
                ArgumentCaptor.forClass(KafkaDeadLetter.class);
        verify(repository).save(captor.capture());
        KafkaDeadLetter stored = captor.getValue();
        assertThat(stored.getOriginalTopic()).isEqualTo("ai-generation-commands");
        assertThat(stored.getRequestId()).isEqualTo(requestId.toString());
        assertThat(stored.getRetryAttempt()).isZero();
        assertThat(stored.getStatus()).isEqualTo(KafkaDeadLetterStatus.PENDING);
        assertThat(stored.getNextRetryAt()).isNotNull();
        verify(outboxService, never()).enqueue(
                any(), any(), any(), any(), any()
        );
    }

    @Test
    void ignoresDuplicateDeliveryOfSameDeadLetterOffset() {
        UUID requestId = UUID.randomUUID();
        ConsumerRecord<String, String> record = record(
                "ai-generation-commands.DLT",
                requestId,
                null
        );
        when(repository.existsByDltTopicAndDltPartitionAndDltOffset(
                record.topic(),
                record.partition(),
                record.offset()
        )).thenReturn(true);

        service.store(record);

        verify(repository, never()).save(any());
        verify(outboxService, never()).enqueue(
                any(), any(), any(), any(), any()
        );
    }

    @Test
    void finalizesVkFailureOnlyAfterDltRetryLimit() {
        UUID requestId = UUID.randomUUID();
        ConsumerRecord<String, String> record = record(
                "vk-outgoing-messages.DLT",
                requestId,
                "3"
        );
        when(outboxRepository.existsByDeduplicationKey(
                requestId + ":VK_DLT_EXHAUSTED"
        )).thenReturn(false);

        service.store(record);

        ArgumentCaptor<KafkaDeadLetter> deadLetterCaptor =
                ArgumentCaptor.forClass(KafkaDeadLetter.class);
        verify(repository).save(deadLetterCaptor.capture());
        assertThat(deadLetterCaptor.getValue().getStatus())
                .isEqualTo(KafkaDeadLetterStatus.EXHAUSTED);
        assertThat(deadLetterCaptor.getValue().getNotifiedAt()).isNotNull();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).enqueue(
                org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.eq(requestId + ":VK_DLT_EXHAUSTED"),
                org.mockito.ArgumentMatchers.eq("vk-message-delivery-results"),
                org.mockito.ArgumentMatchers.eq(requestId.toString()),
                payloadCaptor.capture()
        );
        assertThat(payloadCaptor.getValue())
                .isInstanceOf(VkMessageDeliveryResultEvent.class);
        VkMessageDeliveryResultEvent event =
                (VkMessageDeliveryResultEvent) payloadCaptor.getValue();
        assertThat(event.success()).isFalse();
    }

    @Test
    void notifiesCuratorWhenNonVkDeadLetterIsExhausted() {
        UUID requestId = UUID.randomUUID();
        ConsumerRecord<String, String> record = record(
                "billing-charge-commands.DLT",
                requestId,
                "3"
        );
        WorkflowState workflow = WorkflowState.builder()
                .requestId(requestId)
                .vkChatId("200")
                .vkUserId("300")
                .vkGroupId("100")
                .studentQuestion("Question")
                .status(WorkflowStatus.BILLING_PENDING)
                .build();
        when(workflowRepository.findById(requestId))
                .thenReturn(Optional.of(workflow));
        when(outboxRepository.existsByDeduplicationKey(any()))
                .thenReturn(false);

        service.store(record);

        verify(notificationProducer).send(any());
    }

    private ConsumerRecord<String, String> record(
            String topic,
            UUID requestId,
            String retryAttempt
    ) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(topic, 1, 42L, requestId.toString(), "{}");
        record.headers().add(
                "requestId",
                requestId.toString().getBytes(StandardCharsets.UTF_8)
        );
        if (retryAttempt != null) {
            record.headers().add(
                    KafkaDeadLetterService.RETRY_ATTEMPT_HEADER,
                    retryAttempt.getBytes(StandardCharsets.UTF_8)
            );
        }
        return record;
    }
}
