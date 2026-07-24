package ru.itmo.nemat.orchestrator.dto;

import java.util.UUID;

public record CuratorIntakeDecisionEvent(
        UUID requestId,
        String action,
        String manualAnswer,
        Integer deliveryAttempt
) {
}
