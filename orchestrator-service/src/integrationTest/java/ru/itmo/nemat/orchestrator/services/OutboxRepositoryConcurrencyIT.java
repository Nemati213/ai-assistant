package ru.itmo.nemat.orchestrator.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import ru.itmo.nemat.orchestrator.model.OutboxEvent;
import ru.itmo.nemat.orchestrator.repository.OutboxEventRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
    private PlatformTransactionManager transactionManager;
    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
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
}
