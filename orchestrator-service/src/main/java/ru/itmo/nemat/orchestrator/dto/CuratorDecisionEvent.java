package ru.itmo.nemat.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CuratorDecisionEvent(
        UUID requestId,
        String status,
        String finalAnswer
) {}