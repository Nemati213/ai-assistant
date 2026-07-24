package ru.itmo.nemat.aiservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.aiservice.dto.StudentConversationMessageEvent;
import ru.itmo.nemat.aiservice.service.StudentConversationService;

@Component
@Slf4j
@RequiredArgsConstructor
public class StudentConversationMessageConsumer {

    private final ObjectMapper objectMapper;
    private final StudentConversationService conversationService;

    @KafkaListener(
            topics = "student-conversation-messages",
            groupId = "ai-student-conversations"
    )
    public void consume(String messageJson) {
        try {
            StudentConversationMessageEvent event = objectMapper.readValue(
                    messageJson,
                    StudentConversationMessageEvent.class
            );
            conversationService.recordConversationMessage(event);
            log.info(
                    "[{}] {} message stored in student history",
                    event.requestId(),
                    event.role()
            );
        } catch (Exception exception) {
            log.error("Failed to store delivered student conversation message", exception);
            throw new IllegalStateException(
                    "Failed to store delivered student conversation message",
                    exception
            );
        }
    }
}
