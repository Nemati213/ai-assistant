package ru.itmo.nemat.vkconnector.services;

import org.junit.jupiter.api.Test;
import ru.itmo.nemat.vkconnector.dto.VkCallbackRequest;
import ru.itmo.nemat.vkconnector.dto.VkMessage;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VkRequestIdFactoryTest {

    private final VkRequestIdFactory factory = new VkRequestIdFactory();

    @Test
    void createsSameRequestIdForRepeatedCallbackEvent() {
        VkCallbackRequest request = new VkCallbackRequest(
                "message_new",
                "100",
                "event-42",
                "secret",
                null
        );
        VkMessage message = message(10L, "200");

        UUID first = factory.create(request, message);
        UUID second = factory.create(request, message);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void createsDifferentRequestIdsForDifferentGroups() {
        VkMessage message = message(10L, "200");

        UUID first = factory.create(callback("100", "event-42"), message);
        UUID second = factory.create(callback("101", "event-42"), message);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void fallsBackToMessageIdentityWhenEventIdIsMissing() {
        VkCallbackRequest request = callback("100", null);

        UUID first = factory.create(request, message(10L, "200"));
        UUID second = factory.create(request, message(10L, "200"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void rejectsEventWithoutStableIdentifier() {
        VkCallbackRequest request = callback("100", null);
        VkMessage message = message(null, "200");

        assertThatThrownBy(() -> factory.create(request, message))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private VkCallbackRequest callback(String groupId, String eventId) {
        return new VkCallbackRequest("message_new", groupId, eventId, "secret", null);
    }

    private VkMessage message(Long id, String peerId) {
        return new VkMessage(id, 1L, peerId, "300", "Question", List.of());
    }
}
