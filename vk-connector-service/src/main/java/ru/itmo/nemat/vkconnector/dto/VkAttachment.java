package ru.itmo.nemat.vkconnector.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record VkAttachment(
        @JsonProperty("type") String type,
        @JsonProperty("photo") VkPhoto photo
) {}