package ru.itmo.nemat.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VkMessageEvent(
        UUID requestId,
        String vkChatId,
        String vkUserId,
        String text,
        String vkGroupId,
        Long timestamp,
        List<String> photoUrls
) {}