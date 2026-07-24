package ru.itmo.nemat.aiservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.aiservice.dto.AiGenerationCommand;
import ru.itmo.nemat.aiservice.service.AiGenerationCoordinator;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiCommandConsumer {

    private final ObjectMapper objectMapper;
    private final AiGenerationCoordinator generationCoordinator;

    @KafkaListener(topics = "ai-generation-commands", groupId = "ai-group")
    public void consume(String messageJson) {
        try {
            AiGenerationCommand command =
                    objectMapper.readValue(messageJson, AiGenerationCommand.class);
            log.info("[{}] AI generation command accepted", command.requestId());
            generationCoordinator.process(command);
        } catch (Exception exception) {
            log.error("Failed to process AI generation command", exception);
            throw new IllegalStateException(
                    "Failed to process AI generation command",
                    exception
            );
        }
    }
}
