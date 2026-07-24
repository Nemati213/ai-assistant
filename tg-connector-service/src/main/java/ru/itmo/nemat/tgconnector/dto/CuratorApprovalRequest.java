package ru.itmo.nemat.tgconnector.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CuratorApprovalRequest(
        UUID requestId,
        String vkGroupId,
        String studentQuestion,
        String aiSuggestedAnswer,
        List<String> photoUrls,
        int tokensUsed,
        BigDecimal creditsToCharge
) {}
