package ru.itmo.nemat.orchestrator.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.StudentConversationMessageEvent;
import ru.itmo.nemat.orchestrator.services.OutboxService;

@Component
@Slf4j
@RequiredArgsConstructor
public class StudentConversationProducer {

    private static final String TOPIC = "student-conversation-messages";

    private final OutboxService outboxService;

    public void sendDeliveredAnswer(StudentConversationMessageEvent event) {
        outboxService.enqueue(
                event.requestId(),
                event.requestId() + ":STUDENT_CONVERSATION:ASSISTANT",
                TOPIC,
                event.vkGroupId() + ":" + event.vkUserId(),
                event
        );
        log.debug(
                "[{}] Delivered answer stored in conversation outbox",
                event.requestId()
        );
    }
}
