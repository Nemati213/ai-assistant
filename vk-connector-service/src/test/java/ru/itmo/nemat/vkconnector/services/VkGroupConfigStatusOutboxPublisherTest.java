package ru.itmo.nemat.vkconnector.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.itmo.nemat.vkconnector.model.VkGroupConfigStatusOutboxEvent;
import ru.itmo.nemat.vkconnector.repository.VkGroupConfigStatusOutboxRepository;

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
class VkGroupConfigStatusOutboxPublisherTest {

    @Mock
    private VkGroupConfigStatusOutboxRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private VkGroupConfigStatusOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new VkGroupConfigStatusOutboxPublisher(
                repository,
                kafkaTemplate,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(publisher, "batchSize", 50);
        ReflectionTestUtils.setField(publisher, "publishTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(publisher, "retryBaseDelayMs", 1000L);
        ReflectionTestUtils.setField(publisher, "retryMaxDelayMs", 60000L);
        ReflectionTestUtils.setField(publisher, "retentionDays", 7L);
    }

    @Test
    void marksStatusAsPublishedAfterKafkaAck() {
        VkGroupConfigStatusOutboxEvent event = event();
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
        VkGroupConfigStatusOutboxEvent event = event();
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

    private VkGroupConfigStatusOutboxEvent event() {
        Instant now = Instant.now();
        return VkGroupConfigStatusOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .configVersion(7L)
                .vkGroupId("100")
                .status("ACTIVE")
                .createdAt(now)
                .attempts(0)
                .nextAttemptAt(now)
                .build();
    }
}
