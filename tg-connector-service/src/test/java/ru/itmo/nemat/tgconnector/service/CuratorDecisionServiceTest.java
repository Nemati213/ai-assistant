package ru.itmo.nemat.tgconnector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.tgconnector.dto.CuratorApprovalRequest;
import ru.itmo.nemat.tgconnector.dto.CuratorDecisionEvent;
import ru.itmo.nemat.tgconnector.model.CuratorDecisionOutboxEvent;
import ru.itmo.nemat.tgconnector.model.CuratorDecisionRequest;
import ru.itmo.nemat.tgconnector.model.CuratorDecisionRequestStatus;
import ru.itmo.nemat.tgconnector.repository.CuratorDecisionOutboxRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorDecisionRequestRepository;

import java.math.BigDecimal;
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
class CuratorDecisionServiceTest {

    @Mock
    private CuratorDecisionRequestRepository requestRepository;
    @Mock
    private CuratorDecisionOutboxRepository outboxRepository;

    private CuratorDecisionService service;

    @BeforeEach
    void setUp() {
        service = new CuratorDecisionService(
                requestRepository,
                outboxRepository,
                new ObjectMapper()
        );
    }

    @Test
    void storesApprovalRequestBeforeTelegramDelivery() {
        UUID requestId = UUID.randomUUID();
        CuratorApprovalRequest approval = approval(requestId);
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.empty());
        when(requestRepository.save(any(CuratorDecisionRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<CuratorDecisionService.DecisionView> result =
                service.prepareApproval(approval, 55L);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().currentAnswer()).isEqualTo("AI answer");
        assertThat(result.orElseThrow().revision()).isZero();
        assertThat(result.orElseThrow().approvalMessageId()).isNull();
    }

    @Test
    void queuesEditedApprovalInOutboxExactlyOnce() throws Exception {
        UUID requestId = UUID.randomUUID();
        CuratorDecisionRequest request = request(requestId);
        request.beginEditing(Instant.now());
        request.attachEditPrompt(101, Instant.now());
        request.applyEditedAnswer("Edited answer", 102, Instant.now());
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(request));

        boolean queued = service.queueDecision(
                requestId,
                55L,
                1,
                "APPROVED"
        );

        assertThat(queued).isTrue();
        assertThat(request.getStatus())
                .isEqualTo(CuratorDecisionRequestStatus.DECISION_QUEUED);
        ArgumentCaptor<CuratorDecisionOutboxEvent> captor =
                ArgumentCaptor.forClass(CuratorDecisionOutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        CuratorDecisionEvent event = new ObjectMapper().readValue(
                captor.getValue().getPayload(),
                CuratorDecisionEvent.class
        );
        assertThat(event.requestId()).isEqualTo(requestId);
        assertThat(event.status()).isEqualTo("APPROVED");
        assertThat(event.finalAnswer()).isEqualTo("Edited answer");
    }

    @Test
    void rejectionDoesNotIncludeAnswer() throws Exception {
        UUID requestId = UUID.randomUUID();
        CuratorDecisionRequest request = request(requestId);
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(request));

        boolean queued = service.queueDecision(
                requestId,
                55L,
                0,
                "REJECTED"
        );

        assertThat(queued).isTrue();
        ArgumentCaptor<CuratorDecisionOutboxEvent> captor =
                ArgumentCaptor.forClass(CuratorDecisionOutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        CuratorDecisionEvent event = new ObjectMapper().readValue(
                captor.getValue().getPayload(),
                CuratorDecisionEvent.class
        );
        assertThat(event.status()).isEqualTo("REJECTED");
        assertThat(event.finalAnswer()).isNull();
    }

    @Test
    void staleCardCannotQueueDecisionAfterEdit() {
        UUID requestId = UUID.randomUUID();
        CuratorDecisionRequest request = request(requestId);
        request.beginEditing(Instant.now());
        request.attachEditPrompt(101, Instant.now());
        request.applyEditedAnswer("Edited answer", 102, Instant.now());
        when(requestRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(request));

        boolean queued = service.queueDecision(
                requestId,
                55L,
                0,
                "APPROVED"
        );

        assertThat(queued).isFalse();
        verify(outboxRepository, never()).save(any());
    }

    private CuratorApprovalRequest approval(UUID requestId) {
        return new CuratorApprovalRequest(
                requestId,
                "100",
                "Question",
                "AI answer",
                List.of(),
                25,
                new BigDecimal("1000")
        );
    }

    private CuratorDecisionRequest request(UUID requestId) {
        Instant now = Instant.now();
        return CuratorDecisionRequest.builder()
                .requestId(requestId)
                .tgChatId(55L)
                .vkGroupId("100")
                .studentQuestion("Question")
                .currentAnswer("AI answer")
                .tokensUsed(25)
                .creditsToCharge(new BigDecimal("1000"))
                .status(CuratorDecisionRequestStatus.AWAITING_DECISION)
                .revision(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
