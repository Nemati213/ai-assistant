package ru.itmo.nemat.orchestrator.dto;

import java.util.UUID;

public record BalanceReleaseCommand(
        UUID requestId,
        String reason
) {
}
