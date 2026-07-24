package ru.itmo.nemat.orchestrator.services;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.itmo.nemat.orchestrator.model.OutboxEvent;
import ru.itmo.nemat.orchestrator.repository.OutboxEventRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(repository, kafkaTemplate);
        ReflectionTestUtils.setField(publisher, "batchSize", 50);
        ReflectionTestUtils.setField(publisher, "publishTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(publisher, "retryBaseDelayMs", 1000L);
        ReflectionTestUtils.setField(publisher, "retryMaxDelayMs", 60000L);
        ReflectionTestUtils.setField(publisher, "retentionDays", 7L);
    }

    @Test
    void publishesEventAndMarksItAsDelivered() {
        OutboxEvent event = event();
        when(repository.findReadyForPublishing(any(Instant.class), eq(50)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishReadyEvents();

        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, String> record = recordCaptor.getValue();
        assertThat(record.topic()).isEqualTo("test-topic");
        assertThat(record.key()).isEqualTo("test-key");
        assertThat(record.value()).isEqualTo("{\"value\":1}");
        assertThat(new String(
                record.headers().lastHeader("requestId").value(),
                StandardCharsets.UTF_8
        )).isEqualTo(event.getAggregateId().toString());
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void schedulesRetryWhenKafkaIsUnavailable() {
        OutboxEvent event = event();
        Instant previousAttempt = event.getNextAttemptAt();
        when(repository.findReadyForPublishing(any(Instant.class), eq(50)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("Kafka unavailable")
                ));

        publisher.publishReadyEvents();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("Kafka unavailable");
        assertThat(event.getNextAttemptAt()).isAfter(previousAttempt);
    }

    @Test
    void removesOnlyOldPublishedEvents() {
        when(repository.deletePublishedBefore(any(Instant.class))).thenReturn(3);

        publisher.deletePublishedEvents();

        verify(repository).deletePublishedBefore(any(Instant.class));
    }

    private OutboxEvent event() {
        Instant now = Instant.now();
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .deduplicationKey(UUID.randomUUID() + ":TEST")
                .topic("test-topic")
                .eventKey("test-key")
                .payload("{\"value\":1}")
                .createdAt(now)
                .attempts(0)
                .nextAttemptAt(now)
                .build();
    }
}
