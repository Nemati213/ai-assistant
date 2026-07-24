package ru.itmo.nemat.tgconnector.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceReleaseCommand(
        UUID requestId,
        String reason
) {
}
