package ru.itmo.nemat.orchestrator.services;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import ru.itmo.nemat.orchestrator.config.KafkaDeadLetterProperties;
import ru.itmo.nemat.orchestrator.metrics.KafkaDeadLetterMetrics;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetter;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetterStatus;
import ru.itmo.nemat.orchestrator.repository.KafkaDeadLetterRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
class KafkaDeadLetterRetryPublisherTest {

    @Mock
    private KafkaDeadLetterRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private KafkaDeadLetterMetrics metrics;

    private KafkaDeadLetterRetryPublisher publisher;

    @BeforeEach
    void setUp() {
        KafkaDeadLetterProperties properties = new KafkaDeadLetterProperties();
        properties.setBatchSize(50);
        properties.setPublishTimeout(Duration.ofSeconds(1));
        properties.setRetryBaseDelay(Duration.ofMinutes(1));
        properties.setRetryMaxDelay(Duration.ofMinutes(30));
        publisher = new KafkaDeadLetterRetryPublisher(
                repository,
                kafkaTemplate,
                properties,
                metrics
        );
    }

    @Test
    void restoresBusinessHeadersAndIncrementsDltAttempt() {
        KafkaDeadLetter deadLetter = deadLetter();
        when(repository.findReadyForRetry(any(Instant.class), eq(50)))
                .thenReturn(List.of(deadLetter));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.retryReadyDeadLetters();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("billing-charge-commands");
        assertThat(record.key()).isEqualTo("key");
        assertThat(header(record, "requestId")).isEqualTo("request-1");
        assertThat(header(record, "eventId")).isEqualTo("event-1");
        assertThat(header(record, "configVersion")).isEqualTo("7");
        assertThat(header(record, KafkaDeadLetterService.RETRY_ATTEMPT_HEADER))
                .isEqualTo("2");
        assertThat(deadLetter.getStatus()).isEqualTo(KafkaDeadLetterStatus.RETRIED);
        verify(metrics).recordReplaySuccess();
    }

    @Test
    void schedulesPublisherRetryWhenKafkaIsUnavailable() {
        KafkaDeadLetter deadLetter = deadLetter();
        when(repository.findReadyForRetry(any(Instant.class), eq(50)))
                .thenReturn(List.of(deadLetter));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("Kafka unavailable")
                ));

        publisher.retryReadyDeadLetters();

        assertThat(deadLetter.getStatus())
                .isEqualTo(KafkaDeadLetterStatus.PUBLISH_FAILED);
        assertThat(deadLetter.getLastRetryError()).contains("Kafka unavailable");
        assertThat(deadLetter.getNextRetryAt()).isNotNull();
        verify(metrics).recordReplayFailure();
    }

    private KafkaDeadLetter deadLetter() {
        Instant now = Instant.now();
        return KafkaDeadLetter.builder()
                .id(UUID.randomUUID())
                .dltTopic("billing-charge-commands.DLT")
                .dltPartition(0)
                .dltOffset(10L)
                .originalTopic("billing-charge-commands")
                .eventKey("key")
                .payload("{\"value\":1}")
                .requestId("request-1")
                .eventId("event-1")
                .configVersion("7")
                .retryAttempt(1)
                .status(KafkaDeadLetterStatus.PENDING)
                .receivedAt(now)
                .nextRetryAt(now)
                .build();
    }

    private String header(ProducerRecord<String, String> record, String name) {
        return new String(
                record.headers().lastHeader(name).value(),
                StandardCharsets.UTF_8
        );
    }
}
