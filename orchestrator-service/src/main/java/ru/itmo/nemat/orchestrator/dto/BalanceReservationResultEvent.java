package ru.itmo.nemat.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
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
