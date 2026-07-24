package ru.itmo.nemat.aiservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.aiservice.dto.AiGenerationCommand;
import ru.itmo.nemat.aiservice.dto.AiGenerationResult;
import ru.itmo.nemat.aiservice.dto.ConversationMessage;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGenerationCoordinatorTest {

    @Mock
    private AiGenerationJournalService journalService;
    @Mock
    private OpenRouterService openRouterService;
    @Mock
    private StudentConversationService conversationService;

    @Test
    void generatesOnlyAfterFirstClaim() {
        AiGenerationCommand command = command();
        AiGenerationResult result =
                new AiGenerationResult("Answer", 50, new BigDecimal("0.001"));
        List<ConversationMessage> history = List.of(
                new ConversationMessage("user", "Previous question")
        );
        when(journalService.claim(command)).thenReturn(true);
        when(conversationService.recordQuestionAndLoadHistory(command))
                .thenReturn(history);
        when(openRouterService.generate(command, history)).thenReturn(result);
        AiGenerationCoordinator coordinator =
                new AiGenerationCoordinator(
                        journalService,
                        openRouterService,
                        conversationService
                );

        coordinator.process(command);

        verify(openRouterService).generate(command, history);
        verify(journalService).complete(command.requestId(), result);
    }

    @Test
    void duplicateCommandDoesNotCallProviderAgain() {
        AiGenerationCommand command = command();
        when(journalService.claim(command)).thenReturn(false);
        AiGenerationCoordinator coordinator =
                new AiGenerationCoordinator(
                        journalService,
                        openRouterService,
                        conversationService
                );

        coordinator.process(command);

        verify(conversationService, never()).recordQuestionAndLoadHistory(command);
        verify(openRouterService, never()).generate(
                org.mockito.ArgumentMatchers.eq(command),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void storesProviderFailureWithoutRetryingProviderCall() {
        AiGenerationCommand command = command();
        IllegalStateException failure = new IllegalStateException("OpenRouter unavailable");
        when(journalService.claim(command)).thenReturn(true);
        when(conversationService.recordQuestionAndLoadHistory(command))
                .thenReturn(List.of());
        when(openRouterService.generate(command, List.of())).thenThrow(failure);
        AiGenerationCoordinator coordinator =
                new AiGenerationCoordinator(
                        journalService,
                        openRouterService,
                        conversationService
                );

        coordinator.process(command);

        verify(journalService).fail(command.requestId(), failure);
    }

    @Test
    void propagatesWhenFailureCannotBeStored() {
        AiGenerationCommand command = command();
        IllegalStateException generationFailure =
                new IllegalStateException("OpenRouter unavailable");
        when(journalService.claim(command)).thenReturn(true);
        when(conversationService.recordQuestionAndLoadHistory(command))
                .thenReturn(List.of());
        when(openRouterService.generate(command, List.of()))
                .thenThrow(generationFailure);
        doThrow(new IllegalStateException("Database unavailable"))
                .when(journalService)
                .fail(command.requestId(), generationFailure);
        AiGenerationCoordinator coordinator =
                new AiGenerationCoordinator(
                        journalService,
                        openRouterService,
                        conversationService
                );

        assertThatThrownBy(() -> coordinator.process(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be stored");
    }

    private AiGenerationCommand command() {
        return new AiGenerationCommand(
                UUID.randomUUID(),
                "200",
                "300",
                "100",
                "Question",
                List.of("https://vk.test/photo.jpg"),
                "Prompt"
        );
    }
}
