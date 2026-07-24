package ru.itmo.nemat.aiservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.aiservice.dto.AiGenerationCommand;
import ru.itmo.nemat.aiservice.dto.ConversationMessage;
import ru.itmo.nemat.aiservice.dto.StudentConversationMessageEvent;
import ru.itmo.nemat.aiservice.model.StudentMessage;
import ru.itmo.nemat.aiservice.model.StudentMessageRole;
import ru.itmo.nemat.aiservice.repository.StudentMessageRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StudentConversationService {

    private final StudentMessageRepository messageRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.context.message-limit:20}")
    private int messageLimit;

    @Transactional
    public List<ConversationMessage> recordQuestionAndLoadHistory(
            AiGenerationCommand command
    ) {
        validateIdentity(
                command.vkGroupId(),
                command.vkUserId(),
                command.vkChatId()
        );
        UUID studentId = upsertStudent(
                command.vkGroupId(),
                effectiveVkUserId(command.vkUserId(), command.vkChatId()),
                command.vkChatId(),
                null,
                null,
                null,
                Instant.now()
        );

        List<StudentMessage> recent =
                messageRepository.findRecentMessagesExcludingRequest(
                        studentId,
                        command.requestId(),
                        Math.max(1, messageLimit)
                );
        List<ConversationMessage> history = toChronologicalHistory(recent);

        insertMessage(
                studentId,
                command.requestId(),
                StudentMessageRole.USER,
                command.questionText(),
                command.photoUrls(),
                null,
                "AI_COMMAND",
                Instant.now()
        );
        return history;
    }

    @Transactional
    public void recordConversationMessage(StudentConversationMessageEvent event) {
        validateConversationMessage(event);
        StudentMessageRole role = parseRole(event.role());
        UUID studentId = upsertStudent(
                event.vkGroupId(),
                effectiveVkUserId(event.vkUserId(), event.vkChatId()),
                event.vkChatId(),
                event.firstName(),
                event.lastName(),
                event.displayName(),
                event.occurredAt() == null ? Instant.now() : event.occurredAt()
        );
        insertMessage(
                studentId,
                event.requestId(),
                role,
                normalizedMessageText(event.text(), event.photoUrls()),
                event.photoUrls(),
                event.externalMessageId(),
                normalizedSource(event.source()),
                event.occurredAt() == null
                        ? Instant.now()
                        : event.occurredAt()
        );
    }

    private UUID upsertStudent(
            String vkGroupId,
            String vkUserId,
            String vkChatId,
            String firstName,
            String lastName,
            String displayName,
            Instant now
    ) {
        UUID studentId = deterministicStudentId(vkGroupId, vkUserId);
        jdbcTemplate.update("""
                INSERT INTO students (
                    id,
                    vk_group_id,
                    vk_user_id,
                    latest_vk_chat_id,
                    first_name,
                    last_name,
                    display_name,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (vk_group_id, vk_user_id)
                DO UPDATE SET
                    latest_vk_chat_id = EXCLUDED.latest_vk_chat_id,
                    first_name = COALESCE(
                        NULLIF(EXCLUDED.first_name, ''),
                        students.first_name
                    ),
                    last_name = COALESCE(
                        NULLIF(EXCLUDED.last_name, ''),
                        students.last_name
                    ),
                    display_name = COALESCE(
                        NULLIF(EXCLUDED.display_name, ''),
                        students.display_name
                    ),
                    updated_at = EXCLUDED.updated_at
                """,
                studentId,
                vkGroupId,
                vkUserId,
                vkChatId,
                firstName,
                lastName,
                displayName,
                toDatabaseTime(now),
                toDatabaseTime(now)
        );
        return studentId;
    }

    private void insertMessage(
            UUID studentId,
            UUID requestId,
            StudentMessageRole role,
            String text,
            List<String> photoUrls,
            Long externalMessageId,
            String source,
            Instant createdAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO student_messages (
                    id,
                    student_id,
                    request_id,
                    role,
                    message_text,
                    photo_urls_json,
                    external_message_id,
                    source,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                studentId,
                requestId,
                role.name(),
                text,
                serializePhotoUrls(photoUrls),
                externalMessageId,
                source,
                toDatabaseTime(createdAt)
        );
    }

    private OffsetDateTime toDatabaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private List<ConversationMessage> toChronologicalHistory(
            List<StudentMessage> recent
    ) {
        List<StudentMessage> chronological = new ArrayList<>(recent);
        Collections.reverse(chronological);
        return chronological.stream()
                .map(message -> new ConversationMessage(
                        message.getRole() == StudentMessageRole.USER
                                ? "user"
                                : "assistant",
                        message.getMessageText()
                ))
                .toList();
    }

    private String serializePhotoUrls(List<String> photoUrls) {
        try {
            return objectMapper.writeValueAsString(
                    photoUrls == null ? List.of() : photoUrls
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Failed to serialize student message photos",
                    exception
            );
        }
    }

    private UUID deterministicStudentId(String vkGroupId, String vkUserId) {
        return UUID.nameUUIDFromBytes(
                ("vk:" + vkGroupId + ":" + vkUserId)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private String effectiveVkUserId(String vkUserId, String vkChatId) {
        return vkUserId == null || vkUserId.isBlank()
                ? vkChatId
                : vkUserId;
    }

    private void validateIdentity(
            String vkGroupId,
            String vkUserId,
            String vkChatId
    ) {
        if (vkGroupId == null || vkGroupId.isBlank()) {
            throw new IllegalArgumentException("vkGroupId is required");
        }
        if (vkChatId == null || vkChatId.isBlank()) {
            throw new IllegalArgumentException("vkChatId is required");
        }
        if ((vkUserId == null || vkUserId.isBlank()) && vkChatId.isBlank()) {
            throw new IllegalArgumentException("vkUserId or vkChatId is required");
        }
    }

    private void validateConversationMessage(
            StudentConversationMessageEvent event
    ) {
        if (event == null || event.requestId() == null) {
            throw new IllegalArgumentException("requestId is required");
        }
        validateIdentity(event.vkGroupId(), event.vkUserId(), event.vkChatId());
        if ((event.text() == null || event.text().isBlank())
                && (event.photoUrls() == null || event.photoUrls().isEmpty())) {
            throw new IllegalArgumentException("Conversation message content is required");
        }
    }

    private StudentMessageRole parseRole(String role) {
        try {
            return StudentMessageRole.valueOf(role);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Unsupported conversation role: " + role,
                    exception
            );
        }
    }

    private String normalizedMessageText(
            String text,
            List<String> photoUrls
    ) {
        if (text != null && !text.isBlank()) {
            return text;
        }
        return "[Изображение]";
    }

    private String normalizedSource(String source) {
        return source == null || source.isBlank()
                ? "UNKNOWN"
                : source;
    }
}
