package ru.itmo.nemat.tgconnector.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BalanceReservationResultEvent(
        UUID requestId,
        String status,
        BigDecimal balance,
        BigDecimal availableBalance,
        BigDecimal reservedCredits,
        Instant expiresAt,
        String errorMessage
) {
}
