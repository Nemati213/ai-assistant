package ru.itmo.nemat.aiservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.itmo.nemat.aiservice.dto.AiGenerationCommand;
import ru.itmo.nemat.aiservice.dto.ConversationMessage;
import ru.itmo.nemat.aiservice.dto.StudentConversationMessageEvent;
import ru.itmo.nemat.aiservice.model.StudentMessage;
import ru.itmo.nemat.aiservice.model.StudentMessageRole;
import ru.itmo.nemat.aiservice.repository.StudentMessageRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentConversationServiceTest {

    @Mock
    private StudentMessageRepository messageRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private StudentConversationService service;

    @BeforeEach
    void setUp() {
        service = new StudentConversationService(
                messageRepository,
                jdbcTemplate,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "messageLimit", 20);
    }

    @Test
    void returnsHistoryChronologicallyAndStoresCurrentQuestion() {
        AiGenerationCommand command = command();
        StudentMessage older = message(
                StudentMessageRole.USER,
                "Previous question",
                Instant.parse("2026-01-01T10:00:00Z")
        );
        StudentMessage newer = message(
                StudentMessageRole.ASSISTANT,
                "Previous answer",
                Instant.parse("2026-01-01T10:01:00Z")
        );
        when(messageRepository.findRecentMessagesExcludingRequest(
                any(UUID.class),
                any(UUID.class),
                anyInt()
        ))
                .thenReturn(List.of(newer, older));

        List<ConversationMessage> history =
                service.recordQuestionAndLoadHistory(command);

        assertThat(history).containsExactly(
                new ConversationMessage("user", "Previous question"),
                new ConversationMessage("assistant", "Previous answer")
        );
        InOrder order = inOrder(jdbcTemplate, messageRepository);
        order.verify(jdbcTemplate).update(anyString(), any(Object[].class));
        order.verify(messageRepository).findRecentMessagesExcludingRequest(
                any(UUID.class),
                org.mockito.ArgumentMatchers.eq(command.requestId()),
                anyInt()
        );
        order.verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Test
    void storesDeliveredAnswerOnlyOnce() {
        UUID requestId = UUID.randomUUID();
        StudentConversationMessageEvent event =
                new StudentConversationMessageEvent(
                        requestId,
                        "ASSISTANT",
                        "200",
                        "300",
                        "100",
                        "Иван",
                        "Петров",
                        "Иван Петров",
                        "Edited final answer",
                        List.of(),
                        555L,
                        "VK_MESSAGE_REPLY",
                        Instant.parse("2026-01-01T10:02:00Z")
                );

        service.recordConversationMessage(event);

        ArgumentCaptor<Object[]> profileCaptor =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO students"),
                profileCaptor.capture()
        );
        assertThat(profileCaptor.getValue()[4]).isEqualTo("Иван");
        assertThat(profileCaptor.getValue()[5]).isEqualTo("Петров");
        assertThat(profileCaptor.getValue()[6]).isEqualTo("Иван Петров");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce())
                .update(sqlCaptor.capture(), any(Object[].class));
        assertThat(sqlCaptor.getAllValues())
                .anyMatch(sql -> sql.contains("INSERT INTO student_messages")
                        && sql.contains("ON CONFLICT DO NOTHING"));
    }

    @Test
    void usesAtomicConflictHandlingForDuplicateDeliveredAnswer() {
        UUID requestId = UUID.randomUUID();
        StudentConversationMessageEvent event =
                new StudentConversationMessageEvent(
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
                        555L,
                        "ORCHESTRATOR",
                        Instant.now()
                );

        service.recordConversationMessage(event);

        verify(jdbcTemplate, atLeastOnce())
                .update(
                        org.mockito.ArgumentMatchers.contains("ON CONFLICT DO NOTHING"),
                        any(Object[].class)
                );
    }

    private AiGenerationCommand command() {
        return new AiGenerationCommand(
                UUID.randomUUID(),
                "200",
                "300",
                "100",
                "Current question",
                List.of("https://vk.test/photo.jpg"),
                "Prompt"
        );
    }

    private StudentMessage message(
            StudentMessageRole role,
            String text,
            Instant createdAt
    ) {
        return StudentMessage.builder()
                .id(UUID.randomUUID())
                .studentId(UUID.randomUUID())
                .requestId(UUID.randomUUID())
                .role(role)
                .messageText(text)
                .photoUrlsJson("[]")
                .source("TEST")
                .createdAt(createdAt)
                .build();
    }
}
