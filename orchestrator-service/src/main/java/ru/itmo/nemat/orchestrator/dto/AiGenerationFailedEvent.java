package ru.itmo.nemat.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiGenerationFailedEvent(
        UUID requestId,
        String vkChatId,
        String vkGroupId,
        String errorMessage,
        Instant failedAt
) {
}
