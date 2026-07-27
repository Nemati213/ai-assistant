package ru.itmo.nemat.tgconnector.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.dto.SendVkMessageCommand;
import ru.itmo.nemat.tgconnector.dto.VkMessageDeliveryResultEvent;
import ru.itmo.nemat.tgconnector.model.VkGroupStatus;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BroadcastService {

    private static final int MAX_VK_MESSAGE_LENGTH = 4096;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CuratorVkGroupRepository groupRepository;
    private final StudentDirectoryService directoryService;
    private final int maxRecipients;
    private final int maxTemplateLength;

    public BroadcastService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CuratorVkGroupRepository groupRepository,
            StudentDirectoryService directoryService,
            @Value("${app.broadcast.max-recipients:100}") int maxRecipients,
            @Value("${app.broadcast.max-template-length:3500}")
            int maxTemplateLength
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.groupRepository = groupRepository;
        this.directoryService = directoryService;
        this.maxRecipients = maxRecipients;
        this.maxTemplateLength = maxTemplateLength;
    }

    @Transactional
    public UUID begin(long tgChatId, String vkGroupId) {
        var group = groupRepository
                .findByVkGroupIdAndCuratorTgChatId(vkGroupId, tgChatId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "VK-группа не найдена среди ваших подключений."
                ));
        if (group.getStatus() != VkGroupStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Рассылка доступна только для активной VK-группы."
            );
        }
        if (directoryService.totalEligible(tgChatId, vkGroupId) == 0) {
            throw new IllegalStateException(
                    "В этой группе пока нет учеников с личным диалогом."
            );
        }

        Optional<CampaignRow> active = findActiveCampaign(tgChatId, true);
        if (active.isPresent()) {
            if ("SENDING".equals(active.get().status())) {
                throw new IllegalStateException(
                        "Предыдущая рассылка ещё отправляется."
                );
            }
            jdbcTemplate.update("""
                    UPDATE broadcast_campaigns
                    SET status = 'CANCELLED',
                        updated_at = ?
                    WHERE id = ?
                    """,
                    databaseTime(Instant.now()),
                    active.get().id()
            );
        }

        UUID campaignId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO broadcast_campaigns (
                    id,
                    curator_id,
                    tg_chat_id,
                    vk_group_id,
                    status,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, 'SELECTING', ?, ?)
                """,
                campaignId,
                group.getCurator().getId(),
                tgChatId,
                vkGroupId,
                databaseTime(now),
                databaseTime(now)
        );
        return campaignId;
    }

    @Transactional(readOnly = true)
    public SelectionPage selectionPage(
            long tgChatId,
            int requestedPage,
            int requestedSize
    ) {
        CampaignRow campaign = requireActiveCampaign(tgChatId, false);
        if (!"SELECTING".equals(campaign.status())) {
            throw new IllegalStateException(
                    "Выбор учеников для этой рассылки уже завершён."
            );
        }

        int size = Math.max(1, Math.min(20, requestedSize));
        Integer totalValue = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM curator_students
                WHERE vk_group_id = ?
                  AND direct_vk_chat_id IS NOT NULL
                """,
                Integer.class,
                campaign.vkGroupId()
        );
        int total = totalValue == null ? 0 : totalValue;
        int pageCount = Math.max(1, (total + size - 1) / size);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));

        List<SelectableStudent> students = jdbcTemplate.query("""
                SELECT
                    student.id,
                    student.vk_user_id,
                    student.first_name,
                    student.last_name,
                    student.display_name,
                    EXISTS (
                        SELECT 1
                        FROM broadcast_recipients recipient
                        WHERE recipient.campaign_id = ?
                          AND recipient.student_id = student.id
                    ) AS selected
                FROM curator_students student
                WHERE student.vk_group_id = ?
                  AND student.direct_vk_chat_id IS NOT NULL
                ORDER BY
                    LOWER(COALESCE(NULLIF(student.display_name, ''), student.vk_user_id)),
                    student.vk_user_id
                LIMIT ?
                OFFSET ?
                """,
                (resultSet, rowNumber) -> new SelectableStudent(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("vk_user_id"),
                        resultSet.getString("first_name"),
                        resultSet.getString("last_name"),
                        resultSet.getString("display_name"),
                        resultSet.getBoolean("selected")
                ),
                campaign.id(),
                campaign.vkGroupId(),
                size,
                page * size
        );
        return new SelectionPage(
                campaign.id(),
                campaign.vkGroupId(),
                students,
                page,
                pageCount,
                total,
                selectedCount(campaign.id()),
                maxRecipients
        );
    }

    @Transactional
    public void toggleRecipient(long tgChatId, UUID studentId) {
        CampaignRow campaign = requireActiveCampaign(tgChatId, true);
        if (!"SELECTING".equals(campaign.status())) {
            throw new IllegalStateException("Список получателей уже зафиксирован.");
        }

        Integer eligible = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM curator_students
                WHERE id = ?
                  AND vk_group_id = ?
                  AND direct_vk_chat_id IS NOT NULL
                """,
                Integer.class,
                studentId,
                campaign.vkGroupId()
        );
        if (eligible == null || eligible == 0) {
            throw new IllegalArgumentException(
                    "Ученик не найден в выбранной VK-группе."
            );
        }

        int removed = jdbcTemplate.update("""
                DELETE FROM broadcast_recipients
                WHERE campaign_id = ?
                  AND student_id = ?
                  AND status = 'SELECTED'
                """,
                campaign.id(),
                studentId
        );
        if (removed > 0) {
            return;
        }
        if (selectedCount(campaign.id()) >= maxRecipients) {
            throw new IllegalStateException(
                    "За одну рассылку можно выбрать не больше "
                            + maxRecipients
                            + " учеников."
            );
        }

        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO broadcast_recipients (
                    request_id,
                    campaign_id,
                    student_id,
                    status,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, 'SELECTED', ?, ?)
                ON CONFLICT (campaign_id, student_id) DO NOTHING
                """,
                UUID.randomUUID(),
                campaign.id(),
                studentId,
                databaseTime(now),
                databaseTime(now)
        );
    }

    @Transactional
    public TextRequest requestText(long tgChatId) {
        CampaignRow campaign = requireActiveCampaign(tgChatId, true);
        if (!"SELECTING".equals(campaign.status())) {
            throw new IllegalStateException("Рассылка уже ожидает текст.");
        }
        int count = selectedCount(campaign.id());
        if (count == 0) {
            throw new IllegalStateException("Сначала выберите хотя бы одного ученика.");
        }
        jdbcTemplate.update("""
                UPDATE broadcast_campaigns
                SET status = 'AWAITING_TEXT',
                    updated_at = ?
                WHERE id = ?
                """,
                databaseTime(Instant.now()),
                campaign.id()
        );
        return new TextRequest(campaign.id(), count);
    }

    @Transactional(readOnly = true)
    public boolean isAwaitingText(long tgChatId) {
        return findActiveCampaign(tgChatId, false)
                .map(CampaignRow::status)
                .filter("AWAITING_TEXT"::equals)
                .isPresent();
    }

    @Transactional
    public Preview acceptText(long tgChatId, String rawTemplate) {
        CampaignRow campaign = requireActiveCampaign(tgChatId, true);
        if (!"AWAITING_TEXT".equals(campaign.status())) {
            throw new IllegalStateException("Эта рассылка сейчас не ожидает текст.");
        }
        String template = normalizeTemplate(rawTemplate);
        RecipientRow example = selectedRecipients(campaign.id()).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "В рассылке не осталось получателей."
                ));
        String rendered = render(template, example);

        jdbcTemplate.update("""
                UPDATE broadcast_campaigns
                SET status = 'READY',
                    message_template = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                template,
                databaseTime(Instant.now()),
                campaign.id()
        );
        return new Preview(
                campaign.id(),
                campaign.vkGroupId(),
                selectedCount(campaign.id()),
                template,
                rendered,
                example.label()
        );
    }

    @Transactional
    public QueueResult queue(long tgChatId) {
        CampaignRow campaign = requireActiveCampaign(tgChatId, true);
        if (!"READY".equals(campaign.status())
                || campaign.messageTemplate() == null) {
            throw new IllegalStateException("Рассылка ещё не готова к отправке.");
        }

        List<RecipientRow> recipients = selectedRecipients(campaign.id());
        if (recipients.isEmpty()) {
            throw new IllegalStateException("В рассылке нет получателей.");
        }

        Instant now = Instant.now();
        for (RecipientRow recipient : recipients) {
            String rendered = render(campaign.messageTemplate(), recipient);
            SendVkMessageCommand command = new SendVkMessageCommand(
                    recipient.requestId(),
                    recipient.directVkChatId(),
                    campaign.vkGroupId(),
                    rendered,
                    1
            );
            jdbcTemplate.update("""
                    UPDATE broadcast_recipients
                    SET status = 'QUEUED',
                        rendered_text = ?,
                        updated_at = ?
                    WHERE request_id = ?
                      AND status = 'SELECTED'
                    """,
                    rendered,
                    databaseTime(now),
                    recipient.requestId()
            );
            jdbcTemplate.update("""
                    INSERT INTO broadcast_outbox (
                        event_id,
                        request_id,
                        partition_key,
                        payload,
                        created_at,
                        next_attempt_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (request_id) DO NOTHING
                    """,
                    UUID.randomUUID(),
                    recipient.requestId(),
                    recipient.directVkChatId(),
                    serialize(command),
                    databaseTime(now),
                    databaseTime(now)
            );
        }
        jdbcTemplate.update("""
                UPDATE broadcast_campaigns
                SET status = 'SENDING',
                    updated_at = ?
                WHERE id = ?
                """,
                databaseTime(now),
                campaign.id()
        );
        return new QueueResult(campaign.id(), recipients.size());
    }

    @Transactional
    public Optional<Completion> recordDelivery(
            VkMessageDeliveryResultEvent event
    ) {
        List<RecipientDeliveryRow> matches = jdbcTemplate.query("""
                SELECT recipient.campaign_id, recipient.status, campaign.tg_chat_id
                FROM broadcast_recipients recipient
                JOIN broadcast_campaigns campaign
                  ON campaign.id = recipient.campaign_id
                WHERE recipient.request_id = ?
                FOR UPDATE OF recipient
                """,
                (resultSet, rowNumber) -> new RecipientDeliveryRow(
                        resultSet.getObject("campaign_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("tg_chat_id")
                ),
                event.requestId()
        );
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        RecipientDeliveryRow recipient = matches.get(0);
        if ("SENT".equals(recipient.status())
                || "FAILED".equals(recipient.status())) {
            return Optional.empty();
        }

        jdbcTemplate.update("""
                UPDATE broadcast_recipients
                SET status = ?,
                    vk_message_id = ?,
                    last_error = ?,
                    updated_at = ?
                WHERE request_id = ?
                """,
                event.success() ? "SENT" : "FAILED",
                event.vkMessageId(),
                normalizeError(event.errorMessage()),
                databaseTime(Instant.now()),
                event.requestId()
        );

        CampaignCounts counts = campaignCounts(recipient.campaignId());
        if (counts.terminal() < counts.total()) {
            return Optional.empty();
        }

        String status = counts.failed() == 0
                ? "COMPLETED"
                : "PARTIAL_FAILED";
        jdbcTemplate.update("""
                UPDATE broadcast_campaigns
                SET status = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                status,
                databaseTime(Instant.now()),
                recipient.campaignId()
        );
        return Optional.of(new Completion(
                recipient.campaignId(),
                recipient.tgChatId(),
                counts.total(),
                counts.sent(),
                counts.failed()
        ));
    }

    @Transactional
    public boolean cancel(long tgChatId) {
        Optional<CampaignRow> campaign = findActiveCampaign(tgChatId, true);
        if (campaign.isEmpty() || "SENDING".equals(campaign.get().status())) {
            return false;
        }
        return jdbcTemplate.update("""
                UPDATE broadcast_campaigns
                SET status = 'CANCELLED',
                    updated_at = ?
                WHERE id = ?
                """,
                databaseTime(Instant.now()),
                campaign.get().id()
        ) > 0;
    }

    @Transactional(readOnly = true)
    public List<CampaignSummary> history(long tgChatId, int requestedLimit) {
        int limit = Math.max(1, Math.min(50, requestedLimit));
        return jdbcTemplate.query("""
                SELECT
                    campaign.id,
                    campaign.vk_group_id,
                    campaign.status,
                    campaign.message_template,
                    campaign.created_at,
                    campaign.updated_at,
                    COUNT(recipient.request_id) AS recipients,
                    COUNT(*) FILTER (WHERE recipient.status = 'SENT') AS sent,
                    COUNT(*) FILTER (WHERE recipient.status = 'FAILED') AS failed
                FROM broadcast_campaigns campaign
                LEFT JOIN broadcast_recipients recipient
                  ON recipient.campaign_id = campaign.id
                WHERE campaign.tg_chat_id = ?
                GROUP BY campaign.id
                ORDER BY campaign.created_at DESC
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new CampaignSummary(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("vk_group_id"),
                        resultSet.getString("status"),
                        resultSet.getString("message_template"),
                        resultSet.getInt("recipients"),
                        resultSet.getInt("sent"),
                        resultSet.getInt("failed"),
                        resultSet.getObject(
                                "created_at",
                                OffsetDateTime.class
                        ).toInstant(),
                        resultSet.getObject(
                                "updated_at",
                                OffsetDateTime.class
                        ).toInstant()
                ),
                tgChatId,
                limit
        );
    }

    private CampaignRow requireActiveCampaign(long tgChatId, boolean forUpdate) {
        return findActiveCampaign(tgChatId, forUpdate)
                .orElseThrow(() -> new IllegalStateException(
                        "Активный черновик рассылки не найден."
                ));
    }

    private Optional<CampaignRow> findActiveCampaign(
            long tgChatId,
            boolean forUpdate
    ) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        List<CampaignRow> campaigns = jdbcTemplate.query("""
                        SELECT
                            id,
                            curator_id,
                            tg_chat_id,
                            vk_group_id,
                            status,
                            message_template
                        FROM broadcast_campaigns
                        WHERE tg_chat_id = ?
                          AND status IN (
                              'SELECTING',
                              'AWAITING_TEXT',
                              'READY',
                              'SENDING'
                          )
                        ORDER BY created_at DESC
                        LIMIT 1
                        """ + lock,
                (resultSet, rowNumber) -> new CampaignRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("curator_id", UUID.class),
                        resultSet.getLong("tg_chat_id"),
                        resultSet.getString("vk_group_id"),
                        resultSet.getString("status"),
                        resultSet.getString("message_template")
                ),
                tgChatId
        );
        return campaigns.stream().findFirst();
    }

    private List<RecipientRow> selectedRecipients(UUID campaignId) {
        return jdbcTemplate.query("""
                SELECT
                    recipient.request_id,
                    student.id AS student_id,
                    student.direct_vk_chat_id,
                    student.vk_user_id,
                    student.first_name,
                    student.last_name,
                    student.display_name
                FROM broadcast_recipients recipient
                JOIN curator_students student
                  ON student.id = recipient.student_id
                WHERE recipient.campaign_id = ?
                  AND recipient.status = 'SELECTED'
                ORDER BY
                    LOWER(COALESCE(NULLIF(student.display_name, ''), student.vk_user_id)),
                    student.vk_user_id
                """,
                (resultSet, rowNumber) -> new RecipientRow(
                        resultSet.getObject("request_id", UUID.class),
                        resultSet.getObject("student_id", UUID.class),
                        resultSet.getString("direct_vk_chat_id"),
                        resultSet.getString("vk_user_id"),
                        resultSet.getString("first_name"),
                        resultSet.getString("last_name"),
                        resultSet.getString("display_name")
                ),
                campaignId
        );
    }

    private int selectedCount(UUID campaignId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM broadcast_recipients
                WHERE campaign_id = ?
                  AND status = 'SELECTED'
                """,
                Integer.class,
                campaignId
        );
        return count == null ? 0 : count;
    }

    private CampaignCounts campaignCounts(UUID campaignId) {
        return jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*) AS total,
                    COUNT(*) FILTER (WHERE status IN ('SENT', 'FAILED')) AS terminal,
                    COUNT(*) FILTER (WHERE status = 'SENT') AS sent,
                    COUNT(*) FILTER (WHERE status = 'FAILED') AS failed
                FROM broadcast_recipients
                WHERE campaign_id = ?
                """,
                (resultSet, rowNumber) -> new CampaignCounts(
                        resultSet.getInt("total"),
                        resultSet.getInt("terminal"),
                        resultSet.getInt("sent"),
                        resultSet.getInt("failed")
                ),
                campaignId
        );
    }

    private String normalizeTemplate(String rawTemplate) {
        String template = rawTemplate == null ? "" : rawTemplate.strip();
        if (template.isBlank()) {
            throw new IllegalArgumentException("Текст рассылки не может быть пустым.");
        }
        if (template.length() > maxTemplateLength) {
            throw new IllegalArgumentException(
                    "Текст рассылки слишком длинный. Максимум "
                            + maxTemplateLength
                            + " символов."
            );
        }
        return template;
    }

    private String render(String template, RecipientRow recipient) {
        String displayName = recipient.label();
        String firstName = firstNonBlank(
                recipient.firstName(),
                recipient.displayName(),
                "друг"
        );
        String lastName = firstNonBlank(recipient.lastName(), "");
        String rendered = template
                .replace("{first_name}", firstName)
                .replace("{last_name}", lastName)
                .replace("{name}", displayName);
        if (rendered.length() > MAX_VK_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "После подстановки имени сообщение превышает "
                            + MAX_VK_MESSAGE_LENGTH
                            + " символов."
            );
        }
        return rendered;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private String serialize(SendVkMessageCommand command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Не удалось подготовить команду рассылки.",
                    exception
            );
        }
    }

    private String normalizeError(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    private OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record CampaignRow(
            UUID id,
            UUID curatorId,
            long tgChatId,
            String vkGroupId,
            String status,
            String messageTemplate
    ) {
    }

    private record RecipientRow(
            UUID requestId,
            UUID studentId,
            String directVkChatId,
            String vkUserId,
            String firstName,
            String lastName,
            String displayName
    ) {
        private String label() {
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

    private record RecipientDeliveryRow(
            UUID campaignId,
            String status,
            long tgChatId
    ) {
    }

    private record CampaignCounts(
            int total,
            int terminal,
            int sent,
            int failed
    ) {
    }

    public record SelectableStudent(
            UUID id,
            String vkUserId,
            String firstName,
            String lastName,
            String displayName,
            boolean selected
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

    public record SelectionPage(
            UUID campaignId,
            String vkGroupId,
            List<SelectableStudent> students,
            int page,
            int pageCount,
            int total,
            int selected,
            int maxRecipients
    ) {
    }

    public record Preview(
            UUID campaignId,
            String vkGroupId,
            int recipients,
            String template,
            String renderedExample,
            String exampleStudent
    ) {
    }

    public record TextRequest(UUID campaignId, int recipients) {
    }

    public record QueueResult(UUID campaignId, int recipients) {
    }

    public record Completion(
            UUID campaignId,
            long tgChatId,
            int total,
            int sent,
            int failed
    ) {
    }

    public record CampaignSummary(
            UUID campaignId,
            String vkGroupId,
            String status,
            String messageTemplate,
            int recipients,
            int sent,
            int failed,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
