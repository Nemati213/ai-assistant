package ru.itmo.nemat.tgconnector.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BillingRefundResultEvent(
        UUID requestId,
        String status,
        BigDecimal refundedCredits,
        BigDecimal balanceAfter,
        String errorMessage
) {
}
