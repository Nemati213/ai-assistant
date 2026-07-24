package ru.itmo.nemat.shared.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class KafkaCorrelationHeaders {

    private KafkaCorrelationHeaders() {
    }

    public static void addRequestId(
            ProducerRecord<?, ?> record,
            UUID requestId
    ) {
        addRequestId(record, requestId.toString());
    }

    public static void addRequestId(
            ProducerRecord<?, ?> record,
            String requestId
    ) {
        record.headers().remove(KafkaMdcRecordInterceptor.REQUEST_ID_HEADER);
        record.headers().add(
                KafkaMdcRecordInterceptor.REQUEST_ID_HEADER,
                requestId.getBytes(StandardCharsets.UTF_8)
        );
    }
}
