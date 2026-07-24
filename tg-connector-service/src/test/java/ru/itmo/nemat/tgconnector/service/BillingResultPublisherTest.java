package ru.itmo.nemat.tgconnector.service;

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
import ru.itmo.nemat.tgconnector.model.BillingStatus;
import ru.itmo.nemat.tgconnector.model.BillingTransaction;
import ru.itmo.nemat.tgconnector.repository.BillingTransactionRepository;

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
class BillingResultPublisherTest {

    @Mock
    private BillingTransactionRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    private BillingResultPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new BillingResultPublisher(
                repository,
                kafkaTemplate,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(publisher, "batchSize", 50);
        ReflectionTestUtils.setField(publisher, "timeoutSeconds", 1L);
        ReflectionTestUtils.setField(publisher, "retryBaseDelayMs", 1000L);
        ReflectionTestUtils.setField(publisher, "retryMaxDelayMs", 60000L);
    }

    @Test
    void publishesStoredBillingResult() {
        BillingTransaction transaction = transaction(BillingStatus.CHARGED);
        when(repository.findReadyResults(any(Instant.class), eq(50)))
                .thenReturn(List.of(transaction));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishReadyResults();

        assertThat(transaction.getResultPublishedAt()).isNotNull();
        assertThat(transaction.getLastPublishError()).isNull();
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("billing-results");
        assertThat(captor.getValue().key())
                .isEqualTo(transaction.getRequestId().toString());
    }

    @Test
    void retriesWhenKafkaIsUnavailable() {
        BillingTransaction transaction = transaction(BillingStatus.CHARGED);
        Instant previousAttempt = transaction.getNextPublishAttemptAt();
        when(repository.findReadyResults(any(Instant.class), eq(50)))
                .thenReturn(List.of(transaction));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("Kafka unavailable")
                ));

        publisher.publishReadyResults();

        assertThat(transaction.getResultPublishedAt()).isNull();
        assertThat(transaction.getPublishAttempts()).isEqualTo(1);
        assertThat(transaction.getLastPublishError()).contains("Kafka unavailable");
        assertThat(transaction.getNextPublishAttemptAt()).isAfter(previousAttempt);
    }

    private BillingTransaction transaction(BillingStatus status) {
        Instant now = Instant.now();
        return BillingTransaction.builder()
                .requestId(UUID.randomUUID())
                .curatorId(UUID.randomUUID())
                .vkGroupId("100")
                .aiTokens(30)
                .providerCostUsd(new BigDecimal("0.00015"))
                .creditsCharged(new BigDecimal("100"))
                .creditsPerUsd(new BigDecimal("200000"))
                .minimumCharge(new BigDecimal("100"))
                .status(status)
                .balanceAfter(new BigDecimal("70"))
                .errorMessage(status == BillingStatus.CHARGED
                        ? null
                        : "Insufficient token balance")
                .createdAt(now)
                .publishAttempts(0)
                .nextPublishAttemptAt(now)
                .build();
    }
}
