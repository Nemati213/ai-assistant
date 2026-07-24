package ru.itmo.nemat.vkconnector.dto;

import java.util.UUID;

public record VkMessageDeliveryResultEvent(
        UUID requestId,
        boolean success,
        Long vkMessageId,
        String errorMessage,
        int deliveryAttempt
) {
}
