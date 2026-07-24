package ru.itmo.nemat.orchestrator.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.orchestrator.model.OutboxEvent;
import ru.itmo.nemat.orchestrator.repository.OutboxEventRepository;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(
            UUID aggregateId,
            String deduplicationKey,
            String topic,
            String eventKey,
            Object payload
    ) {
        Instant now = Instant.now();

        repository.save(OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(aggregateId)
                .deduplicationKey(deduplicationKey)
                .topic(topic)
                .eventKey(eventKey)
                .payload(serialize(payload))
                .createdAt(now)
                .attempts(0)
                .nextAttemptAt(now)
                .build());
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize outbox payload", e);
        }
    }
}
