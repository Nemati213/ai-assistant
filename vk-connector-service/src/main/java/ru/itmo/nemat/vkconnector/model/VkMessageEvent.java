package ru.itmo.nemat.vkconnector.model;

import java.util.List;
import java.util.UUID;


public record VkMessageEvent(
        UUID requestId,
        String vkChatId,
        String vkUserId,
        String text,
        String vkGroupId,
        Long timestamp,
        List<String> photoUrls
) {}