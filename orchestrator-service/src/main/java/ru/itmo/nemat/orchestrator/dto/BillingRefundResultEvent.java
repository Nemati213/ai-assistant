package ru.itmo.nemat.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BillingRefundResultEvent(
        UUID requestId,
        String status,
        BigDecimal refundedCredits,
        BigDecimal balanceAfter,
        String errorMessage
) {
}
