package ru.itmo.nemat.orchestrator.dto;

import java.util.List;
import java.util.UUID;

public record AiGenerationCommand(
        UUID requestId,
        String vkChatId,
        String vkUserId,
        String vkGroupId,
        String questionText,
        List<String> photoUrls,
        String systemPrompt
) {}
