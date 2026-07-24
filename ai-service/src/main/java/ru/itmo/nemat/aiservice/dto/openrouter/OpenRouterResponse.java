package ru.itmo.nemat.aiservice.dto.openrouter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenRouterResponse(
        List<Choice> choices,
        Usage usage
) {}