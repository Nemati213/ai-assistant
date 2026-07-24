package ru.itmo.nemat.tgconnector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.itmo.nemat.tgconnector.bot.CuratorTelegramBot;
import ru.itmo.nemat.tgconnector.model.BillingRefund;
import ru.itmo.nemat.tgconnector.model.BillingRefundStatus;
import ru.itmo.nemat.tgconnector.repository.BillingRefundRepository;

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
class BillingRefundResultPublisherTest {

    @Mock
    private BillingRefundRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private CuratorTelegramBot curatorTelegramBot;

    private BillingRefundResultPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new BillingRefundResultPublisher(
                repository,
                kafkaTemplate,
                new ObjectMapper(),
                curatorTelegramBot
        );
        ReflectionTestUtils.setField(publisher, "batchSize", 50);
        ReflectionTestUtils.setField(publisher, "timeoutSeconds", 1L);
        ReflectionTestUtils.setField(publisher, "retryBaseDelayMs", 1000L);
        ReflectionTestUtils.setField(publisher, "retryMaxDelayMs", 60000L);
    }

    @Test
    void publishesStoredRefundAndNotifiesCurator() {
        BillingRefund refund = refund();
        when(repository.findReadyResults(any(Instant.class), eq(50)))
                .thenReturn(List.of(refund));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishReadyResults();

        assertThat(refund.getResultPublishedAt()).isNotNull();
        verify(curatorTelegramBot).sendRefundNotice(
                refund.getCuratorId(),
                refund.getCredits(),
                refund.getBalanceAfter()
        );
    }

    @Test
    void retriesWhenKafkaIsUnavailable() {
        BillingRefund refund = refund();
        Instant previousAttempt = refund.getNextPublishAttemptAt();
        when(repository.findReadyResults(any(Instant.class), eq(50)))
                .thenReturn(List.of(refund));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("Kafka unavailable")
        ));

        publisher.publishReadyResults();

        assertThat(refund.getResultPublishedAt()).isNull();
        assertThat(refund.getPublishAttempts()).isEqualTo(1);
        assertThat(refund.getLastPublishError()).contains("Kafka unavailable");
        assertThat(refund.getNextPublishAttemptAt()).isAfter(previousAttempt);
    }

    private BillingRefund refund() {
        Instant now = Instant.now();
        return BillingRefund.builder()
                .requestId(UUID.randomUUID())
                .curatorId(UUID.randomUUID())
                .credits(new BigDecimal("100"))
                .status(BillingRefundStatus.REFUNDED)
                .balanceAfter(new BigDecimal("150"))
                .reason("VK API error")
                .createdAt(now)
                .publishAttempts(0)
                .nextPublishAttemptAt(now)
                .build();
    }
}
