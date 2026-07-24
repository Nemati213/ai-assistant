package ru.itmo.nemat.aiservice.dto.openrouter;

import java.util.List;

public record OpenRouterRequest(
        String model,
        List<ChatMessage> messages
) {}