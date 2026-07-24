package ru.itmo.nemat.orchestrator.dto;

import java.util.UUID;

public record SendVkMessageCommand(
        UUID requestId,
        String vkChatId,
        String vkGroupId,
        String text,
        int deliveryAttempt
) {}
