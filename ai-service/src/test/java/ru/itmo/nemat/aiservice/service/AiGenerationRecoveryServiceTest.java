package ru.itmo.nemat.aiservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ru.itmo.nemat.aiservice.model.AiGenerationRequest;
import ru.itmo.nemat.aiservice.model.AiGenerationStatus;
import ru.itmo.nemat.aiservice.repository.AiGenerationRequestRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGenerationRecoveryServiceTest {

    @Mock
    private AiGenerationRequestRepository repository;

    private AiGenerationRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new AiGenerationRecoveryService(repository);
        ReflectionTestUtils.setField(service, "processingTimeout", Duration.ofMinutes(2));
        ReflectionTestUtils.setField(service, "batchSize", 50);
    }

    @Test
    void convertsStaleRequestToFailureWithoutProviderRetry() {
        Instant startedAt = Instant.now().minus(Duration.ofMinutes(5));
        AiGenerationRequest request = AiGenerationRequest.builder()
                .requestId(UUID.randomUUID())
                .commandFingerprint("fingerprint")
                .vkChatId("200")
                .vkGroupId("100")
                .status(AiGenerationStatus.PROCESSING)
                .createdAt(startedAt)
                .startedAt(startedAt)
                .publishAttempts(0)
                .nextPublishAttemptAt(startedAt)
                .build();
        when(repository.findStaleProcessing(any(Instant.class), eq(50)))
                .thenReturn(List.of(request));

        service.failStaleRequests();

        assertThat(request.getStatus()).isEqualTo(AiGenerationStatus.FAILED);
        assertThat(request.getErrorMessage()).contains("timed out");
        assertThat(request.getCompletedAt()).isNotNull();
    }
}
