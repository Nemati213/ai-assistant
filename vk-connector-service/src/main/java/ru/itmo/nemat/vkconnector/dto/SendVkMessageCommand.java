package ru.itmo.nemat.vkconnector.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SendVkMessageCommand(
        UUID requestId,
        String vkChatId,
        String vkGroupId,
        String text,
        int deliveryAttempt
) {}
