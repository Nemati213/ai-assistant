package ru.itmo.nemat.vkconnector.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record VkMessage(
        @JsonProperty("id") Long id,
        @JsonProperty("date") Long date,
        @JsonProperty("peer_id") String peerId, // ID чата (откуда пришло)
        @JsonProperty("from_id") String fromId, // ID пользователя (кто написал)
        @JsonProperty("text") String text,
        @JsonProperty("attachments") List<VkAttachment> attachments
) {}