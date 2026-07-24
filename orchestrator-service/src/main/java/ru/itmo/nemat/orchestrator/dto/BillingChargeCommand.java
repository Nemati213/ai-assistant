package ru.itmo.nemat.orchestrator.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BillingChargeCommand(
        UUID requestId,
        String vkGroupId,
        int aiTokens,
        BigDecimal providerCostUsd,
        BigDecimal creditsToCharge,
        BigDecimal creditsPerUsd,
        BigDecimal minimumCharge
) {
}
