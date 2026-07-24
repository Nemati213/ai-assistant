package ru.itmo.nemat.shared.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

public class KafkaMdcRecordInterceptor implements RecordInterceptor<Object, Object> {

    public static final String REQUEST_ID_HEADER = "requestId";
    public static final String EVENT_ID_HEADER = "eventId";

    @Override
    public ConsumerRecord<Object, Object> intercept(
            ConsumerRecord<Object, Object> record,
            Consumer<Object, Object> consumer
    ) {
        clear();
        MDC.put("requestId", correlationId(record));
        MDC.put("kafkaTopic", record.topic());
        MDC.put("kafkaPartition", Integer.toString(record.partition()));
        MDC.put("kafkaOffset", Long.toString(record.offset()));
        return record;
    }

    @Override
    public void afterRecord(
            ConsumerRecord<Object, Object> record,
            Consumer<Object, Object> consumer
    ) {
        clear();
    }

    @Override
    public void clearThreadState(Consumer<?, ?> consumer) {
        clear();
    }

    private String correlationId(ConsumerRecord<Object, Object> record) {
        String requestId = headerValue(record, REQUEST_ID_HEADER);
        if (requestId != null) {
            return requestId;
        }
        String eventId = headerValue(record, EVENT_ID_HEADER);
        if (eventId != null) {
            return eventId;
        }
        return record.key() == null ? "unknown" : record.key().toString();
    }

    private String headerValue(
            ConsumerRecord<Object, Object> record,
            String headerName
    ) {
        Header header = record.headers().lastHeader(headerName);
        if (header == null || header.value() == null || header.value().length == 0) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private void clear() {
        MDC.remove("requestId");
        MDC.remove("kafkaTopic");
        MDC.remove("kafkaPartition");
        MDC.remove("kafkaOffset");
    }
}
