package ru.itmo.nemat.tgconnector.dto;

import java.util.UUID;

public record CuratorIntakeDecisionEvent(
        UUID requestId,
        String action,
        String manualAnswer,
        Integer deliveryAttempt
) {
}
