package ru.itmo.nemat.vkconnector.dto;


import com.fasterxml.jackson.annotation.JsonProperty;

public record VkPhotoSize(
        @JsonProperty("type") String type,
        @JsonProperty("url") String url,
        @JsonProperty("width") Integer width,
        @JsonProperty("height") Integer height
) {}