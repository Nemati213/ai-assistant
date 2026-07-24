package ru.itmo.nemat.aiservice.dto;

import java.math.BigDecimal;
import java.util.UUID;


public record AiAnswerGeneratedEvent(
        UUID requestId,
        String vkChatId,
        String vkGroupId,
        String answerText,
        Integer tokensUsed,
        BigDecimal providerCostUsd
) {}
