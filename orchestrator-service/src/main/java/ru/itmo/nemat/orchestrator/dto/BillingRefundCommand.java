package ru.itmo.nemat.orchestrator.dto;

import java.util.UUID;

public record BillingRefundCommand(
        UUID requestId,
        String reason
) {
}
