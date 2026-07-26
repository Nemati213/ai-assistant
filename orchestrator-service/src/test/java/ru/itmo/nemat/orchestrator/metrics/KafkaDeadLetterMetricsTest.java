package ru.itmo.nemat.orchestrator.metrics;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetterStatus;
import ru.itmo.nemat.orchestrator.repository.KafkaDeadLetterRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaDeadLetterMetricsTest {

    @Mock
    private KafkaDeadLetterRepository repository;

    private PrometheusMeterRegistry meterRegistry;
    private KafkaDeadLetterMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new KafkaDeadLetterMetrics(repository, meterRegistry);
    }

    @Test
    void exposesCurrentBacklogAndOldestRecordAge() {
        when(repository.countByStatus(KafkaDeadLetterStatus.PENDING))
                .thenReturn(4L);
        when(repository.countByStatus(KafkaDeadLetterStatus.PUBLISH_FAILED))
                .thenReturn(2L);
        when(repository.findOldestReceivedAtByStatusIn(List.of(
                KafkaDeadLetterStatus.PENDING,
                KafkaDeadLetterStatus.PUBLISH_FAILED
        ))).thenReturn(Instant.now().minusSeconds(1200));

        metrics.refresh();

        assertThat(meterRegistry.get("curator.kafka.dlt.records")
                .tag("status", "pending")
                .gauge()
                .value()).isEqualTo(4);
        assertThat(meterRegistry.get("curator.kafka.dlt.records")
                .tag("status", "publish_failed")
                .gauge()
                .value()).isEqualTo(2);
        assertThat(meterRegistry.get("curator.kafka.dlt.oldest.age")
                .gauge()
                .value()).isBetween(1199.0, 1201.0);
        assertThat(meterRegistry.scrape())
                .contains("curator_kafka_dlt_records")
                .contains("curator_kafka_dlt_oldest_age_seconds");
    }

    @Test
    void recordsDeadLetterOutcomesAndReplayResults() {
        metrics.recordReceived(KafkaDeadLetterStatus.PENDING);
        metrics.recordReceived(KafkaDeadLetterStatus.EXHAUSTED);
        metrics.recordDuplicate();
        metrics.recordReplaySuccess();
        metrics.recordReplayFailure();

        assertCounter("curator.kafka.dlt.received", "status", "pending");
        assertCounter("curator.kafka.dlt.received", "status", "exhausted");
        assertCounter("curator.kafka.dlt.received", "status", "duplicate");
        assertCounter("curator.kafka.dlt.replays", "outcome", "success");
        assertCounter("curator.kafka.dlt.replays", "outcome", "failure");
        assertThat(meterRegistry.scrape())
                .contains("curator_kafka_dlt_received_total")
                .contains("curator_kafka_dlt_replays_total");
    }

    private void assertCounter(String name, String tag, String value) {
        assertThat(meterRegistry.get(name)
                .tag(tag, value)
                .counter()
                .count()).isEqualTo(1);
    }
}
