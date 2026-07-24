package ru.itmo.nemat.tgconnector.service;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.itmo.nemat.tgconnector.model.VkGroupConfigOutboxEvent;
import ru.itmo.nemat.tgconnector.repository.VkGroupConfigOutboxRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class VkGroupConfigOutboxPublisherTest {

    @Mock
    private VkGroupConfigOutboxRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private VkGroupConfigOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new VkGroupConfigOutboxPublisher(repository, kafkaTemplate);
        ReflectionTestUtils.setField(publisher, "batchSize", 50);
        ReflectionTestUtils.setField(publisher, "publishTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(publisher, "retryBaseDelayMs", 1000L);
        ReflectionTestUtils.setField(publisher, "retryMaxDelayMs", 60000L);
        ReflectionTestUtils.setField(publisher, "retentionDays", 7L);
    }

    @Test
    void marksConfigAsPublishedAfterKafkaAck() {
        VkGroupConfigOutboxEvent event = event();
        when(repository.findReadyForPublishing(any(Instant.class), eq(50)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishReadyEvents();

        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getAttempts()).isZero();
    }

    @Test
    void schedulesRetryWhenKafkaIsUnavailable() {
        VkGroupConfigOutboxEvent event = event();
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
    }

    private VkGroupConfigOutboxEvent event() {
        Instant now = Instant.now();
        return VkGroupConfigOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .vkGroupId("100")
                .configVersion(7L)
                .payload("{\"action\":\"UPSERT\"}")
                .createdAt(now)
                .attempts(0)
                .nextAttemptAt(now)
                .build();
    }
}
