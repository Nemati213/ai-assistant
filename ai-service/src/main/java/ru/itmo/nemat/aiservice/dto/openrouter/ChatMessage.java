package ru.itmo.nemat.aiservice.dto.openrouter;

import java.util.List;

public record ChatMessage(
        String role,
        List<ContentPart> content
) {}