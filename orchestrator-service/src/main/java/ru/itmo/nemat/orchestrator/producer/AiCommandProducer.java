package ru.itmo.nemat.orchestrator.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.AiGenerationCommand;
import ru.itmo.nemat.orchestrator.services.OutboxService;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiCommandProducer {

    private static final String TOPIC = "ai-generation-commands";

    private final OutboxService outboxService;

    public void sendCommand(AiGenerationCommand command) {
        enqueue(command, command.requestId() + ":AI_GENERATION");
    }

    public void resendCommand(AiGenerationCommand command) {
        enqueue(
                command,
                command.requestId() + ":AI_GENERATION:RECOVERY:" + UUID.randomUUID()
        );
    }

    private void enqueue(AiGenerationCommand command, String deduplicationKey) {
        outboxService.enqueue(
                command.requestId(),
                deduplicationKey,
                TOPIC,
                command.vkChatId(),
                command
        );
        log.debug("[{}] AI generation command stored in outbox", command.requestId());
    }
}
