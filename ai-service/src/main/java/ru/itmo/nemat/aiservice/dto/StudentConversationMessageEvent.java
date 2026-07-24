package ru.itmo.nemat.aiservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
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
