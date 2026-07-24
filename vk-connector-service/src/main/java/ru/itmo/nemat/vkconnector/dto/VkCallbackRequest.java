package ru.itmo.nemat.vkconnector.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;


public record VkCallbackRequest(
        @JsonProperty("type") String type,
        @JsonProperty("group_id") String groupId,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("secret") String secret,
        @JsonProperty("object") JsonNode object
) {}
