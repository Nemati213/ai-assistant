package ru.itmo.nemat.tgconnector.dto;

import java.util.UUID;

public record CuratorDecisionEvent(
        UUID requestId,
        String status,
        String finalAnswer
) {}