package ru.itmo.nemat.tgconnector.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
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
