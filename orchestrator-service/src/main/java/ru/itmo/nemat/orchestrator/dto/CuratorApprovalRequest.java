package ru.itmo.nemat.orchestrator.dto;

import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;

public record CuratorApprovalRequest(
        UUID requestId,
        String vkGroupId,
        String studentQuestion,
        String aiSuggestedAnswer,
        List<String> photoUrls,
        int tokensUsed,
        BigDecimal creditsToCharge
) {}
