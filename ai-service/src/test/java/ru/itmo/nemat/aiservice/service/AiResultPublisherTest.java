package ru.itmo.nemat.aiservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.itmo.nemat.aiservice.model.AiGenerationRequest;
import ru.itmo.nemat.aiservice.model.AiGenerationStatus;
import ru.itmo.nemat.aiservice.repository.AiGenerationRequestRepository;

import java.math.BigDecimal;
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
class AiResultPublisherTest {

    @Mock
    private AiGenerationRequestRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private AiResultPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new AiResultPublisher(
                repository,
                kafkaTemplate,
                new ObjectMapper().findAndRegisterModules()
        );
        ReflectionTestUtils.setField(publisher, "batchSize", 50);
        ReflectionTestUtils.setField(publisher, "timeoutSeconds", 1L);
        ReflectionTestUtils.setField(publisher, "retryBaseDelayMs", 1000L);
        ReflectionTestUtils.setField(publisher, "retryMaxDelayMs", 60000L);
    }

    @Test
    void publishesStoredSuccess() {
        AiGenerationRequest request = completed();
        when(repository.findReadyResults(any(Instant.class), eq(50)))
                .thenReturn(List.of(request));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishReadyResults();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("ai-generation-results");
        assertThat(captor.getValue().value()).contains("\"answerText\":\"Answer\"");
        assertThat(request.getResultPublishedAt()).isNotNull();
    }

    @Test
    void publishesStoredFailure() {
        AiGenerationRequest request = processing();
        request.fail("OpenRouter unavailable", Instant.now());
        when(repository.findReadyResults(any(Instant.class), eq(50)))
                .thenReturn(List.of(request));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishReadyResults();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("ai-generation-failures");
        assertThat(captor.getValue().value()).contains("OpenRouter unavailable");
    }

    @Test
    void retriesStoredResultWhenKafkaIsUnavailable() {
        AiGenerationRequest request = completed();
        Instant previousAttempt = request.getNextPublishAttemptAt();
        when(repository.findReadyResults(any(Instant.class), eq(50)))
                .thenReturn(List.of(request));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("Kafka unavailable")
                ));

        publisher.publishReadyResults();

        assertThat(request.getResultPublishedAt()).isNull();
        assertThat(request.getPublishAttempts()).isEqualTo(1);
        assertThat(request.getLastPublishError()).contains("Kafka unavailable");
        assertThat(request.getNextPublishAttemptAt()).isAfter(previousAttempt);
    }

    private AiGenerationRequest completed() {
        AiGenerationRequest request = processing();
        request.complete("Answer", 50, new BigDecimal("0.001"), Instant.now());
        return request;
    }

    private AiGenerationRequest processing() {
        Instant now = Instant.now();
        return AiGenerationRequest.builder()
                .requestId(UUID.randomUUID())
                .commandFingerprint("fingerprint")
                .vkChatId("200")
                .vkGroupId("100")
                .status(AiGenerationStatus.PROCESSING)
                .createdAt(now)
                .startedAt(now)
                .publishAttempts(0)
                .nextPublishAttemptAt(now)
                .build();
    }
}
