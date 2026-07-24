package ru.itmo.nemat.aiservice.dto;

import java.math.BigDecimal;

public record AiGenerationResult(
        String answerText,
        int tokensUsed,
        BigDecimal providerCostUsd
) {
}
