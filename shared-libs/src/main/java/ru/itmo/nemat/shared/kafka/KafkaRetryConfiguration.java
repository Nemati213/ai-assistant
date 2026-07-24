package ru.itmo.nemat.shared.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@Slf4j
public class KafkaRetryConfiguration {

    @Bean
    public RecordInterceptor<Object, Object> kafkaMdcRecordInterceptor() {
        return new KafkaMdcRecordInterceptor();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.kafka.retry.interval-ms:1000}") long retryIntervalMs,
            @Value("${app.kafka.retry.max-attempts:3}") long maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("Kafka retry max attempts must be at least 1");
        }

        ConsumerRecordRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(
                        record.topic().endsWith(".DLT")
                                ? record.topic() + ".PARKING"
                                : record.topic() + ".DLT",
                        -1
                )
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryIntervalMs, maxAttempts - 1)
        );
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn(
                        "Kafka processing failed for {}-{} offset {}, attempt {}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        deliveryAttempt,
                        exception
                )
        );
        return errorHandler;
    }
}
