package ru.itmo.nemat.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.orchestrator.dto.VkMessageEvent;
import ru.itmo.nemat.orchestrator.services.WorkflowOrchestrator;

@Component
@Slf4j
@RequiredArgsConstructor
public class VkMessageConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;


    @KafkaListener(topics = "vk-incoming-messages", groupId = "orchestrator-group")
    public void consume(String messageJson) {
        log.debug("Получено сырое сообщение из Kafka: {}", messageJson);

        try {
            VkMessageEvent event = objectMapper.readValue(messageJson, VkMessageEvent.class);

            log.info("[{}] Событие из ВК успешно получено и десериализовано. Запускаем процесс оркестрации.",
                    event.requestId());

            workflowOrchestrator.startWorkflow(event);

        } catch (Exception e) {
            log.error("Критическая ошибка при чтении или десериализации сообщения из топика vk-incoming-messages: {}",
                    messageJson, e);
            throw new IllegalStateException("Failed to process incoming VK message", e);
        }
    }
}
