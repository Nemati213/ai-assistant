package ru.itmo.nemat.tgconnector.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.dto.BalanceReservationResultEvent;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class BalanceReservationResultProducer {

    private static final String TOPIC = "balance-reservation-results";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void send(BalanceReservationResultEvent event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    event.requestId().toString(),
                    objectMapper.writeValueAsString(event)
            );
            record.headers().add(
                    "requestId",
                    event.requestId().toString().getBytes(StandardCharsets.UTF_8)
            );
            kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            log.error(
                    "[{}] Failed to publish balance reservation result",
                    event.requestId(),
                    exception
            );
            throw new IllegalStateException(
                    "Failed to publish balance reservation result",
                    exception
            );
        }
    }
}
