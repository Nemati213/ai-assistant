package ru.itmo.nemat.vkconnector.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.vkconnector.model.StudentConversationMessageEvent;
import ru.itmo.nemat.vkconnector.model.VkMessageEvent;
import ru.itmo.nemat.vkconnector.model.VkWebhookOutboxEvent;
import ru.itmo.nemat.vkconnector.repository.VkWebhookOutboxRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VkWebhookOutboxServiceTest {

    @Mock
    private VkWebhookOutboxRepository repository;

    @Test
    void storesWorkflowAndHistoryEventsBeforeWebhookAcknowledgement() {
        VkWebhookOutboxService service =
                new VkWebhookOutboxService(
                        repository,
                        new ObjectMapper().findAndRegisterModules()
                );
        UUID requestId = UUID.randomUUID();

        service.enqueueMessageNew(
                new VkMessageEvent(
                        requestId,
                        "200",
                        "300",
                        "Question",
                        "100",
                        1L,
                        List.of()
                ),
                new StudentConversationMessageEvent(
                        requestId,
                        "USER",
                        "200",
                        "300",
                        "100",
                        "Ivan",
                        "Petrov",
                        "Ivan Petrov",
                        "Question",
                        List.of(),
                        501L,
                        "VK_MESSAGE_NEW",
                        Instant.now()
                )
        );

        ArgumentCaptor<VkWebhookOutboxEvent> captor =
                ArgumentCaptor.forClass(VkWebhookOutboxEvent.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(VkWebhookOutboxEvent::getTopic)
                .containsExactly(
                        "vk-incoming-messages",
                        "student-conversation-messages"
                );
        assertThat(captor.getAllValues())
                .extracting(VkWebhookOutboxEvent::getRequestId)
                .containsOnly(requestId);
    }

    @Test
    void ignoresWebhookReplayAlreadyStoredInOutbox() {
        VkWebhookOutboxService service =
                new VkWebhookOutboxService(
                        repository,
                        new ObjectMapper().findAndRegisterModules()
                );
        UUID requestId = UUID.randomUUID();
        when(repository.existsByDeduplicationKey(any())).thenReturn(true);

        service.enqueueConversation(new StudentConversationMessageEvent(
                requestId,
                "ASSISTANT",
                "200",
                "300",
                "100",
                null,
                null,
                null,
                "Answer",
                List.of(),
                777L,
                "VK_MESSAGE_REPLY",
                Instant.now()
        ));

        verify(repository, never()).save(any());
    }
}
