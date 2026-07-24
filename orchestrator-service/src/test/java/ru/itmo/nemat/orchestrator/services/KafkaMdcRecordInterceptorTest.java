package ru.itmo.nemat.orchestrator.services;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import ru.itmo.nemat.shared.kafka.KafkaMdcRecordInterceptor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaMdcRecordInterceptorTest {

    private final KafkaMdcRecordInterceptor interceptor =
            new KafkaMdcRecordInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putsRequestIdAndKafkaCoordinatesIntoMdc() {
        ConsumerRecord<Object, Object> record =
                new ConsumerRecord<>("test-topic", 2, 17L, "key", "{}");
        record.headers().add(
                "requestId",
                "request-123".getBytes(StandardCharsets.UTF_8)
        );

        interceptor.intercept(record, null);

        assertThat(MDC.get("requestId")).isEqualTo("request-123");
        assertThat(MDC.get("kafkaTopic")).isEqualTo("test-topic");
        assertThat(MDC.get("kafkaPartition")).isEqualTo("2");
        assertThat(MDC.get("kafkaOffset")).isEqualTo("17");
    }

    @Test
    void usesEventIdWhenRequestIdIsMissing() {
        ConsumerRecord<Object, Object> record =
                new ConsumerRecord<>("config-topic", 0, 1L, "group", "{}");
        record.headers().add(
                "eventId",
                "event-456".getBytes(StandardCharsets.UTF_8)
        );

        interceptor.intercept(record, null);

        assertThat(MDC.get("requestId")).isEqualTo("event-456");
    }

    @Test
    void clearsMdcAfterRecord() {
        ConsumerRecord<Object, Object> record =
                new ConsumerRecord<>("test-topic", 0, 1L, "key", "{}");
        interceptor.intercept(record, null);

        interceptor.afterRecord(record, null);

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("kafkaTopic")).isNull();
        assertThat(MDC.get("kafkaPartition")).isNull();
        assertThat(MDC.get("kafkaOffset")).isNull();
    }
}
