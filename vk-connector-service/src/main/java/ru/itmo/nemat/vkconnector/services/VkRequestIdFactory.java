package ru.itmo.nemat.vkconnector.services;

import org.springframework.stereotype.Component;
import ru.itmo.nemat.vkconnector.dto.VkCallbackRequest;
import ru.itmo.nemat.vkconnector.dto.VkMessage;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class VkRequestIdFactory {

    public UUID create(VkCallbackRequest request, VkMessage message) {
        String sourceKey;
        if (request.eventId() != null && !request.eventId().isBlank()) {
            sourceKey = "callback:%s:%s".formatted(request.groupId(), request.eventId());
        } else if (message != null && message.id() != null) {
            sourceKey = "message:%s:%s:%s".formatted(
                    request.groupId(),
                    message.peerId(),
                    message.id()
            );
        } else {
            throw new IllegalArgumentException("VK event has no stable identifier");
        }

        return UUID.nameUUIDFromBytes(sourceKey.getBytes(StandardCharsets.UTF_8));
    }
}
