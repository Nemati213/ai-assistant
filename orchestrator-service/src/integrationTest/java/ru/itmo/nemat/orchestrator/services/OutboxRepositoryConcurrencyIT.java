package ru.itmo.nemat.orchestrator.services;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.itmo.nemat.orchestrator.config.KafkaDeadLetterProperties;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetter;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetterStatus;
import ru.itmo.nemat.orchestrator.model.OutboxEvent;
import ru.itmo.nemat.orchestrator.repository.KafkaDeadLetterRepository;
import ru.itmo.nemat.orchestrator.repository.OutboxEventRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@SuppressWarnings("unchecked")
class OutboxRepositoryConcurrencyIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.10-alpine3.23")
                    .withDatabaseName("orchestrator_db")
                    .withUsername("curator_user")
                    .withPassword("curator_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OutboxEventRepository repository;
    @Autowired
    private KafkaDeadLetterRepository deadLetterRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        deadLetterRepository.deleteAll();
        repository.deleteAll();
    }

    @AfterEach
    void stopExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentWorkersLockDifferentReadyEvents() throws Exception {
        Instant now = Instant.now();
        repository.saveAllAndFlush(List.of(
                event("request-1:AI_COMMAND", now.minusSeconds(2)),
                event("request-2:AI_COMMAND", now.minusSeconds(1))
        ));

        executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstRowLocked = new CountDownLatch(1);
        CountDownLatch releaseFirstWorker = new CountDownLatch(1);

        Future<UUID> firstWorker = executor.submit(() ->
                inTransaction(() -> {
                    OutboxEvent locked = readyEvent(now);
                    firstRowLocked.countDown();
                    await(releaseFirstWorker);
                    return locked.getId();
                })
        );

        assertThat(firstRowLocked.await(5, TimeUnit.SECONDS)).isTrue();

        Future<UUID> secondWorker = executor.submit(() ->
                inTransaction(() -> readyEvent(now).getId())
        );

        UUID secondId;
        try {
            secondId = secondWorker.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstWorker.countDown();
        }
        UUID firstId = firstWorker.get(5, TimeUnit.SECONDS);

        assertThat(secondId).isNotEqualTo(firstId);
    }

    @Test
    void databaseRejectsDuplicateDeduplicationKey() {
        Instant now = Instant.now();
        repository.saveAndFlush(event("request-1:AI_COMMAND", now));

        assertThatThrownBy(() ->
                repository.saveAndFlush(event("request-1:AI_COMMAND", now.plusSeconds(1)))
        ).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void deadLetterRetrySurvivesKafkaOutageAndRecoversFromDatabase() {
        KafkaDeadLetter deadLetter = deadLetter();
        deadLetterRepository.saveAndFlush(deadLetter);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("Kafka unavailable")
                ));

        KafkaDeadLetterRetryPublisher failedPublisher = newDeadLetterPublisher();
        inTransaction(() -> {
            failedPublisher.retryReadyDeadLetters();
            return null;
        });

        KafkaDeadLetter failed = deadLetterRepository.findById(deadLetter.getId())
                .orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(KafkaDeadLetterStatus.PUBLISH_FAILED);
        assertThat(failed.getLastRetryError()).contains("Kafka unavailable");
        assertThat(failed.getNextRetryAt()).isNotNull();

        jdbcTemplate.update(
                "UPDATE kafka_dead_letters "
                        + "SET next_retry_at = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                        + "WHERE id = ?",
                deadLetter.getId()
        );
        reset(kafkaTemplate);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        KafkaDeadLetterRetryPublisher restartedPublisher = newDeadLetterPublisher();
        inTransaction(() -> {
            restartedPublisher.retryReadyDeadLetters();
            return null;
        });

        KafkaDeadLetter recovered = deadLetterRepository.findById(deadLetter.getId())
                .orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(KafkaDeadLetterStatus.RETRIED);
        assertThat(recovered.getRetriedAt()).isNotNull();
        assertThat(recovered.getNextRetryAt()).isNull();
        assertThat(recovered.getLastRetryError()).isNull();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> replayed = captor.getValue();
        assertThat(replayed.topic()).isEqualTo("billing-charge-commands");
        assertThat(header(replayed, "requestId")).isEqualTo("request-1");
        assertThat(header(replayed, KafkaDeadLetterService.RETRY_ATTEMPT_HEADER))
                .isEqualTo("2");
    }

    private OutboxEvent readyEvent(Instant now) {
        return repository.findReadyForPublishing(now, 1).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a ready outbox event"));
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            try {
                return action.call();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out while holding an outbox row lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding an outbox row lock", exception);
        }
    }

    private OutboxEvent event(String deduplicationKey, Instant createdAt) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .deduplicationKey(deduplicationKey)
                .topic("ai-generation-commands")
                .eventKey("100")
                .payload("{\"requestId\":\"" + UUID.randomUUID() + "\"}")
                .createdAt(createdAt)
                .attempts(0)
                .nextAttemptAt(createdAt)
                .build();
    }

    private KafkaDeadLetter deadLetter() {
        Instant now = Instant.now();
        return KafkaDeadLetter.builder()
                .id(UUID.randomUUID())
                .dltTopic("billing-charge-commands.DLT")
                .dltPartition(0)
                .dltOffset(42L)
                .originalTopic("billing-charge-commands")
                .eventKey("key")
                .payload("{\"requestId\":\"request-1\"}")
                .requestId("request-1")
                .retryAttempt(1)
                .status(KafkaDeadLetterStatus.PENDING)
                .receivedAt(now)
                .nextRetryAt(now.minusSeconds(1))
                .build();
    }

    private String header(ProducerRecord<String, String> record, String name) {
        return new String(
                record.headers().lastHeader(name).value(),
                StandardCharsets.UTF_8
        );
    }

    private KafkaDeadLetterRetryPublisher newDeadLetterPublisher() {
        KafkaDeadLetterProperties properties = new KafkaDeadLetterProperties();
        properties.setBatchSize(10);
        properties.setPublishTimeout(java.time.Duration.ofSeconds(1));
        properties.setRetryBaseDelay(java.time.Duration.ofMillis(10));
        properties.setRetryMaxDelay(java.time.Duration.ofMillis(100));
        return new KafkaDeadLetterRetryPublisher(
                deadLetterRepository,
                kafkaTemplate,
                properties
        );
    }
}
