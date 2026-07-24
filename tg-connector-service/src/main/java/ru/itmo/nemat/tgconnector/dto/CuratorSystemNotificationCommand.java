package ru.itmo.nemat.tgconnector.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CuratorSystemNotificationCommand(
        UUID requestId,
        String vkGroupId,
        String type,
        String workflowStatus,
        String details,
        Integer deliveryAttempt
) {
}
