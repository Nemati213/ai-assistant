package ru.itmo.nemat.vkconnector.services;

import org.junit.jupiter.api.Test;
import ru.itmo.nemat.vkconnector.dto.VkAttachment;
import ru.itmo.nemat.vkconnector.dto.VkPhoto;
import ru.itmo.nemat.vkconnector.dto.VkPhotoSize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VkPhotoUrlExtractorTest {

    private final VkPhotoUrlExtractor extractor = new VkPhotoUrlExtractor();

    @Test
    void selectsLargestVersionOfEachPhoto() {
        List<String> urls = extractor.extract(List.of(
                photo(
                        size("small", 100, 100),
                        size("largest", 1200, 800),
                        size("medium", 600, 600)
                ),
                photo(
                        size("second-small", 50, 50),
                        size("second-large", 900, 900)
                )
        ));

        assertThat(urls).containsExactly("largest", "second-large");
    }

    @Test
    void ignoresMalformedAttachmentsAndDuplicateUrls() {
        List<String> urls = extractor.extract(List.of(
                new VkAttachment("doc", null),
                new VkAttachment("photo", new VkPhoto(null)),
                photo(new VkPhotoSize("x", "", null, null)),
                photo(size("same", 100, 100)),
                photo(size("same", 200, 200))
        ));

        assertThat(urls).containsExactly("same");
    }

    private VkAttachment photo(VkPhotoSize... sizes) {
        return new VkAttachment("photo", new VkPhoto(List.of(sizes)));
    }

    private VkPhotoSize size(String url, Integer width, Integer height) {
        return new VkPhotoSize("x", url, width, height);
    }
}
