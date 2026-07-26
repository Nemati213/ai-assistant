package ru.itmo.nemat.orchestrator.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetterStatus;
import ru.itmo.nemat.orchestrator.repository.KafkaDeadLetterRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class KafkaDeadLetterMetrics {

    private static final List<KafkaDeadLetterStatus> ACTIVE_STATUSES = List.of(
            KafkaDeadLetterStatus.PENDING,
            KafkaDeadLetterStatus.PUBLISH_FAILED
    );

    private final KafkaDeadLetterRepository repository;
    private final AtomicLong pendingRecords = new AtomicLong();
    private final AtomicLong publishFailedRecords = new AtomicLong();
    private final Counter pendingReceived;
    private final Counter exhaustedReceived;
    private final Counter duplicateReceived;
    private final Counter replaySucceeded;
    private final Counter replayFailed;

    private volatile Instant oldestActiveReceivedAt;

    public KafkaDeadLetterMetrics(
            KafkaDeadLetterRepository repository,
            MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        Gauge.builder(
                        "curator.kafka.dlt.records",
                        pendingRecords,
                        AtomicLong::get
                )
                .description("Current number of dead letters by active status")
                .tag("status", "pending")
                .register(meterRegistry);
        Gauge.builder(
                        "curator.kafka.dlt.records",
                        publishFailedRecords,
                        AtomicLong::get
                )
                .description("Current number of dead letters by active status")
                .tag("status", "publish_failed")
                .register(meterRegistry);
        Gauge.builder(
                        "curator.kafka.dlt.oldest.age",
                        this,
                        KafkaDeadLetterMetrics::oldestActiveAgeSeconds
                )
                .description("Age of the oldest active dead letter")
                .baseUnit("seconds")
                .register(meterRegistry);

        pendingReceived = counter(
                meterRegistry,
                "pending",
                "New dead letters received by status"
        );
        exhaustedReceived = counter(
                meterRegistry,
                "exhausted",
                "New dead letters received by status"
        );
        duplicateReceived = counter(
                meterRegistry,
                "duplicate",
                "New dead letters received by status"
        );
        replaySucceeded = replayCounter(meterRegistry, "success");
        replayFailed = replayCounter(meterRegistry, "failure");
    }

    @Scheduled(
            fixedDelayString = "${app.dead-letter.metrics-refresh-ms:15000}",
            initialDelayString = "${app.dead-letter.metrics-initial-delay-ms:0}"
    )
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            pendingRecords.set(repository.countByStatus(
                    KafkaDeadLetterStatus.PENDING
            ));
            publishFailedRecords.set(repository.countByStatus(
                    KafkaDeadLetterStatus.PUBLISH_FAILED
            ));
            oldestActiveReceivedAt =
                    repository.findOldestReceivedAtByStatusIn(ACTIVE_STATUSES);
        } catch (RuntimeException exception) {
            log.warn("Failed to refresh Kafka dead letter metrics", exception);
        }
    }

    public void recordReceived(KafkaDeadLetterStatus status) {
        if (status == KafkaDeadLetterStatus.EXHAUSTED) {
            exhaustedReceived.increment();
            return;
        }
        pendingReceived.increment();
    }

    public void recordDuplicate() {
        duplicateReceived.increment();
    }

    public void recordReplaySuccess() {
        replaySucceeded.increment();
    }

    public void recordReplayFailure() {
        replayFailed.increment();
    }

    private Counter counter(
            MeterRegistry meterRegistry,
            String status,
            String description
    ) {
        return Counter.builder("curator.kafka.dlt.received")
                .description(description)
                .tag("status", status)
                .register(meterRegistry);
    }

    private Counter replayCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("curator.kafka.dlt.replays")
                .description("Dead letter replay attempts by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private double oldestActiveAgeSeconds() {
        Instant oldest = oldestActiveReceivedAt;
        if (oldest == null) {
            return 0;
        }
        return Math.max(
                0,
                Duration.between(oldest, Instant.now()).toMillis() / 1000.0
        );
    }
}
