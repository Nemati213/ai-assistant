package ru.itmo.nemat.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiAnswerGeneratedEvent(
        UUID requestId,
        String vkChatId,
        String vkGroupId,
        String answerText,
        Integer tokensUsed,
        BigDecimal providerCostUsd
) {}
