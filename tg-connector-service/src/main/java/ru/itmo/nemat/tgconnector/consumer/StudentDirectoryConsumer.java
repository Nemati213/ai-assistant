package ru.itmo.nemat.tgconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.dto.StudentConversationMessageEvent;
import ru.itmo.nemat.tgconnector.service.StudentDirectoryService;

@Component
@Slf4j
@RequiredArgsConstructor
public class StudentDirectoryConsumer {

    private final ObjectMapper objectMapper;
    private final StudentDirectoryService directoryService;

    @KafkaListener(
            topics = "student-conversation-messages",
            groupId = "tg-student-directory"
    )
    public void consume(String payload) {
        try {
            StudentConversationMessageEvent event = objectMapper.readValue(
                    payload,
                    StudentConversationMessageEvent.class
            );
            directoryService.record(event);
        } catch (Exception exception) {
            log.error("Failed to update curator student directory", exception);
            throw new IllegalStateException(
                    "Failed to update curator student directory",
                    exception
            );
        }
    }
}
