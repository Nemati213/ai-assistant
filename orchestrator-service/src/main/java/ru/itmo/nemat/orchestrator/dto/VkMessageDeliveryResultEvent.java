package ru.itmo.nemat.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VkMessageDeliveryResultEvent(
        UUID requestId,
        boolean success,
        Long vkMessageId,
        String errorMessage,
        int deliveryAttempt
) {
}
