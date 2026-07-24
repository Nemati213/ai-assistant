package ru.itmo.nemat.tgconnector.dto;

import java.util.UUID;

public record VkGroupConfigEvent(
        UUID eventId,
        long configVersion,
        String action,
        String vkGroupId,
        String vkToken,
        String vkSecret,
        String vkConfirmationCode,
        String systemPrompt
) {}
