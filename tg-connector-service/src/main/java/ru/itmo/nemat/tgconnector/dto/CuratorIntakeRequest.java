package ru.itmo.nemat.tgconnector.dto;

import java.util.List;
import java.util.UUID;

public record CuratorIntakeRequest(
        UUID requestId,
        String vkGroupId,
        String studentQuestion,
        List<String> photoUrls
) {
}
