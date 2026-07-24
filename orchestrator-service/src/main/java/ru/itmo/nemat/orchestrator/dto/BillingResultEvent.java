package ru.itmo.nemat.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BillingResultEvent(
        UUID requestId,
        String status,
        BigDecimal chargedCredits,
        BigDecimal balanceAfter,
        String errorMessage
) {
}
