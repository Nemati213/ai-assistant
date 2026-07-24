package ru.itmo.nemat.aiservice.dto;

import java.time.Instant;
import java.util.UUID;

public record AiGenerationFailedEvent(
        UUID requestId,
        String vkChatId,
        String vkGroupId,
        String errorMessage,
        Instant failedAt
) {
}
