package ru.itmo.nemat.vkconnector.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.vkconnector.dto.VkCallbackRequest;
import ru.itmo.nemat.vkconnector.model.StudentConversationMessageEvent;
import ru.itmo.nemat.vkconnector.model.VkGroupCredentials;
import ru.itmo.nemat.vkconnector.model.VkMessageEvent;
import ru.itmo.nemat.vkconnector.model.VkUserProfile;
import ru.itmo.nemat.vkconnector.repository.VkGroupCredentialsRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VkWebhookServiceTest {

    @Mock
    private VkGroupCredentialsRepository repository;
    @Mock
    private VkPhotoUrlExtractor photoUrlExtractor;
    @Mock
    private VkWebhookOutboxService webhookOutboxService;
    @Mock
    private VkUserProfileService userProfileService;

    private ObjectMapper objectMapper;
    private VkWebhookService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new VkWebhookService(
                repository,
                objectMapper,
                new VkRequestIdFactory(),
                photoUrlExtractor,
                webhookOutboxService,
                userProfileService
        );
        VkGroupCredentials credentials = new VkGroupCredentials();
        credentials.setVkGroupId("100");
        credentials.setVkSecret("secret");
        when(repository.findById("100")).thenReturn(Optional.of(credentials));
    }

    @Test
    void storesIncomingMessageBeforeStartingWorkflow() throws Exception {
        arrangeProfile();
        VkCallbackRequest request = request(
                "message_new",
                "event-new",
                501L,
                "300",
                "300",
                "Question"
        );

        assertThat(service.handleWebhook(request)).isEqualTo("ok");

        ArgumentCaptor<StudentConversationMessageEvent> conversationCaptor =
                ArgumentCaptor.forClass(StudentConversationMessageEvent.class);
        ArgumentCaptor<VkMessageEvent> incomingCaptor =
                ArgumentCaptor.forClass(VkMessageEvent.class);
        verify(webhookOutboxService).enqueueMessageNew(
                incomingCaptor.capture(),
                conversationCaptor.capture()
        );
        assertThat(conversationCaptor.getValue().role()).isEqualTo("USER");
        assertThat(conversationCaptor.getValue().vkUserId()).isEqualTo("300");
        assertThat(conversationCaptor.getValue().externalMessageId()).isEqualTo(501L);
        assertThat(conversationCaptor.getValue().displayName()).isEqualTo("Иван Петров");
        assertThat(incomingCaptor.getValue().text()).isEqualTo("Question");
    }

    @Test
    void storesManualCommunityReplyWithoutStartingAiWorkflow() throws Exception {
        arrangeProfile();
        VkCallbackRequest request = request(
                "message_reply",
                "event-reply",
                777L,
                "300",
                "-100",
                "Manual curator answer"
        );

        assertThat(service.handleWebhook(request)).isEqualTo("ok");

        ArgumentCaptor<StudentConversationMessageEvent> captor =
                ArgumentCaptor.forClass(StudentConversationMessageEvent.class);
        verify(webhookOutboxService).enqueueConversation(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo("ASSISTANT");
        assertThat(captor.getValue().vkUserId()).isEqualTo("300");
        assertThat(captor.getValue().text())
                .isEqualTo("Manual curator answer");
        assertThat(captor.getValue().externalMessageId()).isEqualTo(777L);
        assertThat(captor.getValue().firstName()).isEqualTo("Иван");
        verify(webhookOutboxService, never()).enqueueMessageNew(any(), any());
    }

    @Test
    void storesManualCommunityReplyFromDirectVkPayload() throws Exception {
        arrangeProfile();
        VkCallbackRequest request = new VkCallbackRequest(
                "message_reply",
                "100",
                "event-direct-reply",
                "secret",
                objectMapper.readTree("""
                        {
                          "id": 779,
                          "date": 1760000000,
                          "peer_id": "300",
                          "from_id": "-100",
                          "text": "Direct payload answer",
                          "attachments": []
                        }
                        """)
        );

        assertThat(service.handleWebhook(request)).isEqualTo("ok");

        ArgumentCaptor<StudentConversationMessageEvent> captor =
                ArgumentCaptor.forClass(StudentConversationMessageEvent.class);
        verify(webhookOutboxService).enqueueConversation(captor.capture());
        assertThat(captor.getValue().text()).isEqualTo("Direct payload answer");
        assertThat(captor.getValue().externalMessageId()).isEqualTo(779L);
        verify(webhookOutboxService, never()).enqueueMessageNew(any(), any());
    }

    @Test
    void skipsManualReplyInMultiUserConversation() throws Exception {
        VkCallbackRequest request = request(
                "message_reply",
                "event-chat-reply",
                778L,
                "2000000001",
                "-100",
                "Conversation answer"
        );

        assertThat(service.handleWebhook(request)).isEqualTo("ok");

        verify(webhookOutboxService, never()).enqueueConversation(any());
        verify(webhookOutboxService, never()).enqueueMessageNew(any(), any());
    }

    private VkCallbackRequest request(
            String type,
            String eventId,
            long messageId,
            String peerId,
            String fromId,
            String text
    ) throws Exception {
        return new VkCallbackRequest(
                type,
                "100",
                eventId,
                "secret",
                objectMapper.readTree("""
                        {
                          "message": {
                            "id": %d,
                            "date": 1760000000,
                            "peer_id": "%s",
                            "from_id": "%s",
                            "text": "%s",
                            "attachments": []
                          }
                        }
                        """.formatted(
                        messageId,
                        peerId,
                        fromId,
                        text
                ))
        );
    }

    private void arrangeProfile() {
        when(userProfileService.resolve("100", "300"))
                .thenReturn(Optional.of(new VkUserProfile(
                        "Иван",
                        "Петров",
                        "Иван Петров"
                )));
    }
}
