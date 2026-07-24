package ru.itmo.nemat.vkconnector.services;

import org.springframework.stereotype.Component;
import ru.itmo.nemat.vkconnector.dto.VkAttachment;
import ru.itmo.nemat.vkconnector.dto.VkPhotoSize;

import java.util.Comparator;
import java.util.List;

@Component
public class VkPhotoUrlExtractor {

    public List<String> extract(List<VkAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }

        return attachments.stream()
                .filter(attachment -> attachment != null)
                .filter(attachment -> "photo".equals(attachment.type()))
                .filter(attachment -> attachment.photo() != null)
                .filter(attachment -> attachment.photo().sizes() != null)
                .map(attachment -> attachment.photo().sizes().stream()
                        .filter(size -> size != null)
                        .filter(size -> size.url() != null && !size.url().isBlank())
                        .max(Comparator.comparingLong(this::area))
                        .map(VkPhotoSize::url)
                        .orElse(null))
                .filter(url -> url != null)
                .distinct()
                .toList();
    }

    private long area(VkPhotoSize size) {
        long width = size.width() == null ? 0 : Math.max(0, size.width());
        long height = size.height() == null ? 0 : Math.max(0, size.height());
        return width * height;
    }
}
