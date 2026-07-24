package ru.itmo.nemat.orchestrator.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.SendVkMessageCommand;
import ru.itmo.nemat.orchestrator.services.OutboxService;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class VkMessageProducer {

    private static final String TOPIC = "vk-outgoing-messages";

    private final OutboxService outboxService;

    public void sendCommand(SendVkMessageCommand command) {
        enqueue(command, command.requestId() + ":VK_OUTGOING");
    }

    public void resendCommand(SendVkMessageCommand command) {
        enqueue(
                command,
                command.requestId() + ":VK_OUTGOING:RECOVERY:" + UUID.randomUUID()
        );
    }

    private void enqueue(SendVkMessageCommand command, String deduplicationKey) {
        outboxService.enqueue(
                command.requestId(),
                deduplicationKey,
                TOPIC,
                command.vkChatId(),
                command
        );
        log.debug("[{}] VK outgoing command stored in outbox", command.requestId());
    }
}
