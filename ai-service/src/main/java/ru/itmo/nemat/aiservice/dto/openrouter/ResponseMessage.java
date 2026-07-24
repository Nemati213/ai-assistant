package ru.itmo.nemat.aiservice.dto.openrouter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ResponseMessage(
        String role,
        String content
) {}