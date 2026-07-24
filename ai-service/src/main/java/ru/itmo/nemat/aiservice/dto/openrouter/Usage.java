package ru.itmo.nemat.aiservice.dto.openrouter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Usage(
        @JsonProperty("total_tokens") Integer totalTokens,
        BigDecimal cost
) {}
