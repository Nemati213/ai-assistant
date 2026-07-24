package ru.itmo.nemat.orchestrator.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.services.KafkaDeadLetterService;

@Component
@Slf4j
@RequiredArgsConstructor
public class KafkaDeadLetterConsumer {

    private final KafkaDeadLetterService deadLetterService;

    @KafkaListener(
            topicPattern = ".*\\.DLT",
            groupId = "orchestrator-dead-letter-registry"
    )
    public void consume(ConsumerRecord<String, String> record) {
        deadLetterService.store(record);
    }
}
