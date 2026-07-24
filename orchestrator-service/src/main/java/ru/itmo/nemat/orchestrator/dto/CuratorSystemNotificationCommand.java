package ru.itmo.nemat.orchestrator.dto;

import java.util.UUID;

public record CuratorSystemNotificationCommand(
        UUID requestId,
        String vkGroupId,
        String type,
        String workflowStatus,
        String details,
        Integer deliveryAttempt
) {
}
