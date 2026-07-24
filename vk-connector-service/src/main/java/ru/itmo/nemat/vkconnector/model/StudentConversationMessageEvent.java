package ru.itmo.nemat.vkconnector.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StudentConversationMessageEvent(
        UUID requestId,
        String role,
        String vkChatId,
        String vkUserId,
        String vkGroupId,
        String firstName,
        String lastName,
        String displayName,
        String text,
        List<String> photoUrls,
        Long externalMessageId,
        String source,
        Instant occurredAt
) {
}
