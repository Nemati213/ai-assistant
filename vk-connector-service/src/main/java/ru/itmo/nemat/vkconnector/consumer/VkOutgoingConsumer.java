package ru.itmo.nemat.vkconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.vkconnector.dto.SendVkMessageCommand;
import ru.itmo.nemat.vkconnector.services.VkOutgoingDeliveryService;

@Component
@Slf4j
@RequiredArgsConstructor
public class VkOutgoingConsumer {

    private final ObjectMapper objectMapper;
    private final VkOutgoingDeliveryService deliveryService;

    @KafkaListener(topics = "vk-outgoing-messages", groupId = "vk-group")
    public void consume(String messageJson) {
        SendVkMessageCommand command;
        try {
            command = objectMapper.readValue(messageJson, SendVkMessageCommand.class);
        } catch (Exception exception) {
            log.error("Failed to deserialize VK outgoing command", exception);
            throw new IllegalStateException("Failed to deserialize VK outgoing command", exception);
        }

        deliveryService.deliver(command);
    }
}
