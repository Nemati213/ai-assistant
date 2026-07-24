package ru.itmo.nemat.aiservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.itmo.nemat.aiservice.dto.AiGenerationCommand;
import ru.itmo.nemat.aiservice.dto.AiGenerationResult;
import ru.itmo.nemat.aiservice.dto.ConversationMessage;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiGenerationCoordinator {

    private final AiGenerationJournalService journalService;
    private final OpenRouterService openRouterService;
    private final StudentConversationService conversationService;

    public void process(AiGenerationCommand command) {
        if (!journalService.claim(command)) {
            log.info(
                    "[{}] Duplicate AI command ignored; existing journal entry will publish result",
                    command.requestId()
            );
            return;
        }

        try {
            List<ConversationMessage> history =
                    conversationService.recordQuestionAndLoadHistory(command);
            AiGenerationResult result = openRouterService.generate(command, history);
            journalService.complete(command.requestId(), result);
        } catch (Exception generationError) {
            try {
                journalService.fail(command.requestId(), generationError);
                log.warn(
                        "[{}] AI generation failed and failure was stored",
                        command.requestId(),
                        generationError
                );
            } catch (Exception persistenceError) {
                generationError.addSuppressed(persistenceError);
                throw new IllegalStateException(
                        "AI generation failed and failure could not be stored",
                        generationError
                );
            }
        }
    }
}
