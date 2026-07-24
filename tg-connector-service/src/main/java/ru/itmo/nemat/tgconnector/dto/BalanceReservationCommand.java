package ru.itmo.nemat.tgconnector.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceReservationCommand(
        UUID requestId,
        String vkGroupId,
        BigDecimal reservedCredits,
        Instant expiresAt
) {
}
