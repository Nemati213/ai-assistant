package ru.itmo.nemat.vkconnector.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record VkPhoto(
        @JsonProperty("sizes") List<VkPhotoSize> sizes
) {}