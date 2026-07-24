package ru.itmo.nemat.tgconnector.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BillingResultEvent(
        UUID requestId,
        String status,
        BigDecimal chargedCredits,
        BigDecimal balanceAfter,
        String errorMessage
) {
}
