package ru.itmo.nemat.aiservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.aiservice.dto.AiGenerationCommand;
import ru.itmo.nemat.aiservice.dto.AiGenerationResult;
import ru.itmo.nemat.aiservice.model.AiGenerationRequest;
import ru.itmo.nemat.aiservice.model.AiGenerationStatus;
import ru.itmo.nemat.aiservice.repository.AiGenerationRequestRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGenerationJournalServiceTest {

    @Mock
    private AiGenerationRequestRepository repository;
    @Mock
    private AiCommandFingerprint fingerprint;

    private AiGenerationJournalService service;

    @BeforeEach
    void setUp() {
        service = new AiGenerationJournalService(repository, fingerprint);
    }

    @Test
    void claimsNewRequestBeforeProviderCall() {
        AiGenerationCommand command = command();
        when(fingerprint.calculate(command)).thenReturn("fingerprint");
        when(repository.findByIdForUpdate(command.requestId()))
                .thenReturn(Optional.empty());

        assertThat(service.claim(command)).isTrue();

        ArgumentCaptor<AiGenerationRequest> captor =
                ArgumentCaptor.forClass(AiGenerationRequest.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiGenerationStatus.PROCESSING);
        assertThat(captor.getValue().getCommandFingerprint()).isEqualTo("fingerprint");
    }

    @Test
    void acceptsExactDuplicateWithoutNewClaim() {
        AiGenerationCommand command = command();
        when(fingerprint.calculate(command)).thenReturn("fingerprint");
        when(repository.findByIdForUpdate(command.requestId()))
                .thenReturn(Optional.of(processing(command.requestId(), "fingerprint")));

        assertThat(service.claim(command)).isFalse();
    }

    @Test
    void rejectsReusedRequestIdWithDifferentPayload() {
        AiGenerationCommand command = command();
        when(fingerprint.calculate(command)).thenReturn("new-fingerprint");
        when(repository.findByIdForUpdate(command.requestId()))
                .thenReturn(Optional.of(processing(command.requestId(), "old-fingerprint")));

        assertThatThrownBy(() -> service.claim(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different command data");
    }

    @Test
    void completesClaimedRequest() {
        UUID requestId = UUID.randomUUID();
        AiGenerationRequest request = processing(requestId, "fingerprint");
        when(repository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

        service.complete(
                requestId,
                new AiGenerationResult("Answer", 50, new BigDecimal("0.001"))
        );

        assertThat(request.getStatus()).isEqualTo(AiGenerationStatus.COMPLETED);
        assertThat(request.getAnswerText()).isEqualTo("Answer");
        assertThat(request.getTokensUsed()).isEqualTo(50);
        assertThat(request.getProviderCostUsd()).isEqualByComparingTo("0.001");
    }

    private AiGenerationCommand command() {
        return new AiGenerationCommand(
                UUID.randomUUID(),
                "200",
                "300",
                "100",
                "Question",
                List.of(),
                "Prompt"
        );
    }

    private AiGenerationRequest processing(UUID requestId, String commandFingerprint) {
        Instant now = Instant.now();
        return AiGenerationRequest.builder()
                .requestId(requestId)
                .commandFingerprint(commandFingerprint)
                .vkChatId("200")
                .vkGroupId("100")
                .status(AiGenerationStatus.PROCESSING)
                .createdAt(now)
                .startedAt(now)
                .publishAttempts(0)
                .nextPublishAttemptAt(now)
                .build();
    }
}
