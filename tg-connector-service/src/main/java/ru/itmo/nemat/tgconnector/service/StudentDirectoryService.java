package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.dto.StudentConversationMessageEvent;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StudentDirectoryService {

    private static final int MAX_PAGE_SIZE = 50;

    private final JdbcTemplate jdbcTemplate;
    private final CuratorVkGroupRepository groupRepository;

    @Transactional
    public void record(StudentConversationMessageEvent event) {
        if (!isValid(event)) {
            throw new IllegalArgumentException("Invalid student conversation event");
        }
        if (!groupRepository.existsByVkGroupId(event.vkGroupId())) {
            log.debug(
                    "[{}] Student event ignored for unknown VK group {}",
                    event.requestId(),
                    event.vkGroupId()
            );
            return;
        }

        Instant occurredAt = event.occurredAt() == null
                ? Instant.now()
                : event.occurredAt();
        String directChatId = event.vkChatId().equals(event.vkUserId())
                ? event.vkChatId()
                : null;
        OffsetDateTime databaseTime =
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
        boolean inbound = "USER".equals(event.role());

        jdbcTemplate.update("""
                INSERT INTO curator_students (
                    id,
                    vk_group_id,
                    vk_user_id,
                    direct_vk_chat_id,
                    first_name,
                    last_name,
                    display_name,
                    first_seen_at,
                    last_seen_at,
                    last_inbound_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (vk_group_id, vk_user_id)
                DO UPDATE SET
                    direct_vk_chat_id = COALESCE(
                        EXCLUDED.direct_vk_chat_id,
                        curator_students.direct_vk_chat_id
                    ),
                    first_name = COALESCE(
                        NULLIF(EXCLUDED.first_name, ''),
                        curator_students.first_name
                    ),
                    last_name = COALESCE(
                        NULLIF(EXCLUDED.last_name, ''),
                        curator_students.last_name
                    ),
                    display_name = COALESCE(
                        NULLIF(EXCLUDED.display_name, ''),
                        curator_students.display_name
                    ),
                    last_seen_at = GREATEST(
                        EXCLUDED.last_seen_at,
                        curator_students.last_seen_at
                    ),
                    last_inbound_at = CASE
                        WHEN EXCLUDED.last_inbound_at IS NULL
                            THEN curator_students.last_inbound_at
                        WHEN curator_students.last_inbound_at IS NULL
                            THEN EXCLUDED.last_inbound_at
                        ELSE GREATEST(
                            EXCLUDED.last_inbound_at,
                            curator_students.last_inbound_at
                        )
                    END
                """,
                deterministicStudentId(event.vkGroupId(), event.vkUserId()),
                event.vkGroupId(),
                event.vkUserId(),
                directChatId,
                normalized(event.firstName()),
                normalized(event.lastName()),
                normalized(event.displayName()),
                databaseTime,
                databaseTime,
                inbound ? databaseTime : null
        );
    }

    @Transactional(readOnly = true)
    public StudentPage page(
            long tgChatId,
            String vkGroupId,
            int requestedPage,
            int requestedSize
    ) {
        int size = Math.max(1, Math.min(MAX_PAGE_SIZE, requestedSize));
        int total = totalEligible(tgChatId, vkGroupId);
        int pageCount = Math.max(1, (total + size - 1) / size);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));

        List<StudentView> students = jdbcTemplate.query("""
                SELECT
                    student.id,
                    student.vk_user_id,
                    student.direct_vk_chat_id,
                    student.first_name,
                    student.last_name,
                    student.display_name,
                    student.last_inbound_at
                FROM curator_students student
                JOIN curator_vk_groups vk_group
                  ON vk_group.vk_group_id = student.vk_group_id
                JOIN curators curator
                  ON curator.id = vk_group.curator_id
                WHERE curator.tg_chat_id = ?
                  AND student.vk_group_id = ?
                  AND student.direct_vk_chat_id IS NOT NULL
                ORDER BY
                    LOWER(COALESCE(NULLIF(student.display_name, ''), student.vk_user_id)),
                    student.vk_user_id
                LIMIT ?
                OFFSET ?
                """,
                (resultSet, rowNumber) -> new StudentView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("vk_user_id"),
                        resultSet.getString("direct_vk_chat_id"),
                        resultSet.getString("first_name"),
                        resultSet.getString("last_name"),
                        resultSet.getString("display_name"),
                        toInstant(resultSet.getObject(
                                "last_inbound_at",
                                OffsetDateTime.class
                        ))
                ),
                tgChatId,
                vkGroupId,
                size,
                page * size
        );
        return new StudentPage(vkGroupId, students, page, pageCount, total);
    }

    @Transactional(readOnly = true)
    public int totalEligible(long tgChatId, String vkGroupId) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM curator_students student
                JOIN curator_vk_groups vk_group
                  ON vk_group.vk_group_id = student.vk_group_id
                JOIN curators curator
                  ON curator.id = vk_group.curator_id
                WHERE curator.tg_chat_id = ?
                  AND student.vk_group_id = ?
                  AND student.direct_vk_chat_id IS NOT NULL
                """,
                Integer.class,
                tgChatId,
                vkGroupId
        );
        return total == null ? 0 : total;
    }

    private boolean isValid(StudentConversationMessageEvent event) {
        return event != null
                && event.requestId() != null
                && event.vkGroupId() != null
                && !event.vkGroupId().isBlank()
                && event.vkUserId() != null
                && !event.vkUserId().isBlank()
                && event.vkChatId() != null
                && !event.vkChatId().isBlank();
    }

    private UUID deterministicStudentId(String vkGroupId, String vkUserId) {
        return UUID.nameUUIDFromBytes(
                ("vk:" + vkGroupId + ":" + vkUserId)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record StudentView(
            UUID id,
            String vkUserId,
            String directVkChatId,
            String firstName,
            String lastName,
            String displayName,
            Instant lastInboundAt
    ) {
        public String label() {
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
            String fullName = String.join(
                    " ",
                    firstName == null ? "" : firstName,
                    lastName == null ? "" : lastName
            ).strip();
            return fullName.isBlank() ? "VK " + vkUserId : fullName;
        }
    }

    public record StudentPage(
            String vkGroupId,
            List<StudentView> students,
            int page,
            int pageCount,
            int total
    ) {
    }
}
