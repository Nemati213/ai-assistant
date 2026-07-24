package ru.itmo.nemat.tgconnector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.tgconnector.dto.CuratorIntakeDecisionEvent;
import ru.itmo.nemat.tgconnector.dto.CuratorIntakeRequest;
import ru.itmo.nemat.tgconnector.model.CuratorIntakeOutboxEvent;
import ru.itmo.nemat.tgconnector.model.CuratorIntakeRequestState;
import ru.itmo.nemat.tgconnector.model.CuratorIntakeStatus;
import ru.itmo.nemat.tgconnector.repository.CuratorIntakeOutboxRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorIntakeRequestRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CuratorIntakeServiceTest {

    @Mock
    private CuratorIntakeRequestRepository requestRepository;
    @Mock
    private CuratorIntakeOutboxRepository outboxRepository;

    private CuratorIntakeService service;

    @BeforeEach
    void setUp() {
        service = new CuratorIntakeService(
                requestRepository,
                outboxRepository,
                new ObjectMapper()
        );
    }

    @Test
    void storesIncomingQuestionBeforeTelegramDelivery() {
        UUID requestId = UUID.randomUUID();
        CuratorIntakeRequest intake = new CuratorIntakeRequest(
                requestId,
                "100",
                "Question",
                List.of("https://vk.test/photo.jpg")
        );
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.empty());
        when(requestRepository.save(any(CuratorIntakeRequestState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<CuratorIntakeService.IntakeView> result =
                service.prepare(intake, 55L);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().studentQuestion()).isEqualTo("Question");
    }

    @Test
    void queuesAiChoiceExactlyOnce() throws Exception {
        UUID requestId = UUID.randomUUID();
        CuratorIntakeRequestState state = state(requestId);
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        boolean queued = service.queueAi(requestId, 55L);

        assertThat(queued).isTrue();
        assertThat(state.getStatus()).isEqualTo(CuratorIntakeStatus.DECISION_QUEUED);
        ArgumentCaptor<CuratorIntakeOutboxEvent> captor =
                ArgumentCaptor.forClass(CuratorIntakeOutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        CuratorIntakeDecisionEvent event = new ObjectMapper().readValue(
                captor.getValue().getPayload(),
                CuratorIntakeDecisionEvent.class
        );
        assertThat(event.action()).isEqualTo("SEND_TO_AI");
        assertThat(event.manualAnswer()).isNull();
        assertThat(captor.getValue().getDeduplicationKey())
                .isEqualTo(requestId + ":INITIAL");
    }

    @Test
    void exposesOriginalTelegramCardForUiCleanup() {
        UUID requestId = UUID.randomUUID();
        CuratorIntakeRequestState state = state(requestId);
        state.markDelivered(77, Instant.now());
        when(requestRepository.findById(requestId))
                .thenReturn(Optional.of(state));

        Optional<CuratorIntakeService.IntakeView> result =
                service.findView(requestId, 55L);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().intakeMessageId()).isEqualTo(77);
    }

    @Test
    void queuesManualAnswerFromMatchingForceReply() throws Exception {
        UUID requestId = UUID.randomUUID();
        CuratorIntakeRequestState state = state(requestId);
        state.beginManualReply(Instant.now());
        state.attachManualPrompt(101, Instant.now());
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        boolean queued = service.completeManualReply(
                requestId,
                55L,
                101,
                "Manual answer"
        );

        assertThat(queued).isTrue();
        ArgumentCaptor<CuratorIntakeOutboxEvent> captor =
                ArgumentCaptor.forClass(CuratorIntakeOutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        CuratorIntakeDecisionEvent event = new ObjectMapper().readValue(
                captor.getValue().getPayload(),
                CuratorIntakeDecisionEvent.class
        );
        assertThat(event.action()).isEqualTo("MANUAL_ANSWER");
        assertThat(event.manualAnswer()).isEqualTo("Manual answer");
    }

    @Test
    void ignoresSecondChoiceAfterDecisionWasQueued() {
        UUID requestId = UUID.randomUUID();
        CuratorIntakeRequestState state = state(requestId);
        state.queueDecision(Instant.now());
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        boolean queued = service.queueAi(requestId, 55L);

        assertThat(queued).isFalse();
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void reopensActionChoiceWhenCuratorCancelsManualTyping() {
        UUID requestId = UUID.randomUUID();
        CuratorIntakeRequestState state = state(requestId);
        state.markDelivered(90, Instant.now());
        state.beginManualReply(Instant.now());
        state.attachManualPrompt(101, Instant.now());
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        boolean reopened = service.reopenAfterManualCancellation(
                requestId,
                55L,
                101
        );

        assertThat(reopened).isTrue();
        assertThat(state.getStatus()).isEqualTo(CuratorIntakeStatus.AWAITING_ACTION);
        assertThat(state.getIntakeMessageId()).isNull();
        assertThat(state.getManualPromptMessageId()).isNull();
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void queuesVersionedRetryAfterManualDeliveryFailure() throws Exception {
        UUID requestId = UUID.randomUUID();
        CuratorIntakeRequestState state = state(requestId);
        state.queueDecision(Instant.now());
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        Optional<CuratorIntakeService.IntakeView> failure =
                service.prepareManualDeliveryFailure(
                        requestId,
                        55L,
                        1,
                        "VK API error 5"
                );
        assertThat(failure).isPresent();
        service.markManualDeliveryFailureDelivered(requestId, 55L, 1, 202);

        boolean queued = service.queueManualDeliveryAction(
                requestId,
                55L,
                1,
                false
        );

        assertThat(queued).isTrue();
        assertThat(state.getStatus())
                .isEqualTo(CuratorIntakeStatus.RECOVERY_ACTION_QUEUED);
        ArgumentCaptor<CuratorIntakeOutboxEvent> captor =
                ArgumentCaptor.forClass(CuratorIntakeOutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        CuratorIntakeDecisionEvent event = new ObjectMapper().readValue(
                captor.getValue().getPayload(),
                CuratorIntakeDecisionEvent.class
        );
        assertThat(event.action()).isEqualTo("RETRY_MANUAL_DELIVERY");
        assertThat(event.deliveryAttempt()).isEqualTo(1);
        assertThat(captor.getValue().getDeduplicationKey())
                .isEqualTo(requestId + ":MANUAL_DELIVERY:1");
    }

    @Test
    void ignoresDuplicateFailureNotificationAfterCardWasDelivered() {
        UUID requestId = UUID.randomUUID();
        CuratorIntakeRequestState state = state(requestId);
        state.queueDecision(Instant.now());
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(state));

        assertThat(service.prepareManualDeliveryFailure(
                requestId,
                55L,
                1,
                "VK error"
        )).isPresent();
        service.markManualDeliveryFailureDelivered(requestId, 55L, 1, 202);

        assertThat(service.prepareManualDeliveryFailure(
                requestId,
                55L,
                1,
                "VK error"
        )).isEmpty();
    }

    private CuratorIntakeRequestState state(UUID requestId) {
        Instant now = Instant.now();
        return CuratorIntakeRequestState.builder()
                .requestId(requestId)
                .tgChatId(55L)
                .vkGroupId("100")
                .studentQuestion("Question")
                .status(CuratorIntakeStatus.AWAITING_ACTION)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
