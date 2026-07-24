package ru.itmo.nemat.vkconnector.dto;

import java.util.UUID;

public record VkGroupConfigStatusEvent(
        UUID eventId,
        long configVersion,
        String vkGroupId,
        String status,
        String errorMessage
) {
}
