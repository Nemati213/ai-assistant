package ru.itmo.nemat.vkconnector.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.vkconnector.model.StudentConversationMessageEvent;
import ru.itmo.nemat.vkconnector.model.VkMessageEvent;
import ru.itmo.nemat.vkconnector.model.VkWebhookOutboxEvent;
import ru.itmo.nemat.vkconnector.repository.VkWebhookOutboxRepository;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VkWebhookOutboxService {

    private static final String INCOMING_TOPIC = "vk-incoming-messages";
    private static final String CONVERSATION_TOPIC = "student-conversation-messages";

    private final VkWebhookOutboxRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void enqueueMessageNew(
            VkMessageEvent incoming,
            StudentConversationMessageEvent conversation
    ) {
        enqueue(
                incoming.requestId(),
                incoming.requestId() + ":INCOMING",
                INCOMING_TOPIC,
                incoming.vkChatId(),
                incoming
        );
        enqueue(
                conversation.requestId(),
                conversation.requestId() + ":CONVERSATION:" + conversation.role(),
                CONVERSATION_TOPIC,
                conversation.vkGroupId() + ":" + conversation.vkUserId(),
                conversation
        );
    }

    @Transactional
    public void enqueueConversation(StudentConversationMessageEvent conversation) {
        enqueue(
                conversation.requestId(),
                conversation.requestId() + ":CONVERSATION:" + conversation.role(),
                CONVERSATION_TOPIC,
                conversation.vkGroupId() + ":" + conversation.vkUserId(),
                conversation
        );
    }

    private void enqueue(
            UUID requestId,
            String deduplicationKey,
            String topic,
            String eventKey,
            Object payload
    ) {
        if (repository.existsByDeduplicationKey(deduplicationKey)) {
            return;
        }
        Instant now = Instant.now();
        repository.save(VkWebhookOutboxEvent.builder()
                .id(UUID.randomUUID())
                .deduplicationKey(deduplicationKey)
                .requestId(requestId)
                .topic(topic)
                .eventKey(eventKey)
                .payload(serialize(payload))
                .createdAt(now)
                .nextAttemptAt(now)
                .build());
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Failed to serialize VK webhook outbox payload",
                    exception
            );
        }
    }
}
