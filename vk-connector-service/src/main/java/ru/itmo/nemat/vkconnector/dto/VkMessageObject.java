package ru.itmo.nemat.vkconnector.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VkMessageObject(
        @JsonProperty("message") VkMessage message
) {}