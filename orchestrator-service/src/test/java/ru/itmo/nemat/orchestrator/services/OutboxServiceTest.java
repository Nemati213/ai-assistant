package ru.itmo.nemat.orchestrator.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.orchestrator.model.OutboxEvent;
import ru.itmo.nemat.orchestrator.repository.OutboxEventRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxService outboxService;

    @Test
    void storesSerializedEventForLaterDelivery() throws Exception {
        UUID requestId = UUID.randomUUID();
        Object payload = new Object();
        when(objectMapper.writeValueAsString(payload)).thenReturn("{\"value\":1}");

        outboxService.enqueue(
                requestId,
                requestId + ":TEST",
                "test-topic",
                "test-key",
                payload
        );

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());

        OutboxEvent event = captor.getValue();
        assertThat(event.getAggregateId()).isEqualTo(requestId);
        assertThat(event.getDeduplicationKey()).isEqualTo(requestId + ":TEST");
        assertThat(event.getTopic()).isEqualTo("test-topic");
        assertThat(event.getEventKey()).isEqualTo("test-key");
        assertThat(event.getPayload()).isEqualTo("{\"value\":1}");
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getNextAttemptAt()).isEqualTo(event.getCreatedAt());
    }
}
