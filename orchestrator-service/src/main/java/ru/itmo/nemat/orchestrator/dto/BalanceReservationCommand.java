package ru.itmo.nemat.orchestrator.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BalanceReservationCommand(
        UUID requestId,
        String vkGroupId,
        BigDecimal reservedCredits,
        Instant expiresAt
) {
}
