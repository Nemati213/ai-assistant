package ru.itmo.nemat.tgconnector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.itmo.nemat.tgconnector.config.SecretEncryptionConfig;
import ru.itmo.nemat.tgconnector.dto.SendVkMessageCommand;
import ru.itmo.nemat.tgconnector.dto.StudentConversationMessageEvent;
import ru.itmo.nemat.tgconnector.dto.VkMessageDeliveryResultEvent;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.model.Subject;
import ru.itmo.nemat.tgconnector.model.VkGroupStatus;
import ru.itmo.nemat.tgconnector.persistence.EncryptedStringConverter;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;
import ru.itmo.nemat.tgconnector.repository.SubjectRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "app.security.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.broadcast.max-recipients=10",
        "app.broadcast.max-template-length=3500"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({
        StudentDirectoryService.class,
        BroadcastService.class,
        BroadcastOutboxPublisher.class,
        SecretEncryptionConfig.class,
        EncryptedStringConverter.class
})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BroadcastLifecycleIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.10-alpine3.23")
                    .withDatabaseName("tg_connector_db")
                    .withUsername("curator_user")
                    .withPassword("curator_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private StudentDirectoryService directoryService;
    @Autowired
    private BroadcastService broadcastService;
    @Autowired
    private BroadcastOutboxPublisher outboxPublisher;
    @Autowired
    private SubjectRepository subjectRepository;
    @Autowired
    private CuratorRepository curatorRepository;
    @Autowired
    private CuratorVkGroupRepository groupRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM broadcast_outbox");
        jdbcTemplate.update("DELETE FROM broadcast_recipients");
        jdbcTemplate.update("DELETE FROM broadcast_campaigns");
        jdbcTemplate.update("DELETE FROM curator_students");
        jdbcTemplate.update("DELETE FROM curator_vk_groups");
        jdbcTemplate.update("DELETE FROM curators");
        jdbcTemplate.update("DELETE FROM subjects");
    }

    @Test
    void selectedStudentsReceiveOnePersonalizedIdempotentMessage() throws Exception {
        seedCuratorAndGroup();
        UUID conversationRequestId = UUID.randomUUID();
        directoryService.record(new StudentConversationMessageEvent(
                conversationRequestId,
                "USER",
                "200",
                "200",
                "100",
                "Анна",
                "Иванова",
                "Анна Иванова",
                "Вопрос",
                List.of(),
                10L,
                "VK_MESSAGE_NEW",
                Instant.now()
        ));
        directoryService.record(new StudentConversationMessageEvent(
                conversationRequestId,
                "USER",
                "200",
                "200",
                "100",
                "Анна",
                "Иванова",
                "Анна Иванова",
                "Вопрос",
                List.of(),
                10L,
                "VK_MESSAGE_NEW",
                Instant.now()
        ));
        directoryService.record(new StudentConversationMessageEvent(
                UUID.randomUUID(),
                "USER",
                "2000000001",
                "300",
                "100",
                "Иван",
                "Петров",
                "Иван Петров",
                "Сообщение из общей беседы",
                List.of(),
                11L,
                "VK_MESSAGE_NEW",
                Instant.now()
        ));

        StudentDirectoryService.StudentPage directory =
                directoryService.page(123L, "100", 0, 8);
        assertThat(directory.total()).isEqualTo(1);
        assertThat(directory.students()).singleElement()
                .satisfies(student -> {
                    assertThat(student.label()).isEqualTo("Анна Иванова");
                    assertThat(student.directVkChatId()).isEqualTo("200");
                });

        broadcastService.begin(123L, "100");
        BroadcastService.SelectionPage selection =
                broadcastService.selectionPage(123L, 0, 8);
        UUID studentId = selection.students().get(0).id();
        broadcastService.toggleRecipient(123L, studentId);
        assertThat(broadcastService.selectionPage(123L, 0, 8).selected())
                .isEqualTo(1);

        BroadcastService.TextRequest textRequest =
                broadcastService.requestText(123L);
        assertThat(textRequest.recipients()).isEqualTo(1);
        BroadcastService.Preview preview = broadcastService.acceptText(
                123L,
                "Привет, {first_name}! Это персональная мотивация."
        );
        assertThat(preview.renderedExample())
                .isEqualTo("Привет, Анна! Это персональная мотивация.");

        BroadcastService.QueueResult queued = broadcastService.queue(123L);
        assertThat(queued.recipients()).isEqualTo(1);
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM broadcast_outbox",
                String.class
        );
        SendVkMessageCommand command =
                objectMapper.readValue(payload, SendVkMessageCommand.class);
        assertThat(command.vkChatId()).isEqualTo("200");
        assertThat(command.vkGroupId()).isEqualTo("100");
        assertThat(command.text())
                .isEqualTo("Привет, Анна! Это персональная мотивация.");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishReadyEvents();

        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());
        assertThat(recordCaptor.getValue().topic())
                .isEqualTo("vk-outgoing-messages");
        assertThat(recordCaptor.getValue().key()).isEqualTo("200");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM broadcast_outbox",
                Boolean.class
        )).isTrue();

        BroadcastService.Completion completion = broadcastService.recordDelivery(
                new VkMessageDeliveryResultEvent(
                        command.requestId(),
                        true,
                        777L,
                        null,
                        1
                )
        ).orElseThrow();
        assertThat(completion.sent()).isEqualTo(1);
        assertThat(completion.failed()).isZero();
        assertThat(broadcastService.recordDelivery(
                new VkMessageDeliveryResultEvent(
                        command.requestId(),
                        true,
                        777L,
                        null,
                        1
                )
        )).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM broadcast_campaigns WHERE id = ?",
                String.class,
                queued.campaignId()
        )).isEqualTo("COMPLETED");
    }

    private void seedCuratorAndGroup() {
        Subject subject = subjectRepository.saveAndFlush(Subject.builder()
                .code("MATH")
                .name("Математика")
                .systemPrompt("Отвечай понятно")
                .build());
        Curator curator = curatorRepository.saveAndFlush(Curator.builder()
                .tgChatId(123L)
                .username("curator")
                .subject(subject)
                .balanceTokens(new BigDecimal("1000"))
                .build());
        groupRepository.saveAndFlush(CuratorVkGroup.builder()
                .curator(curator)
                .vkGroupId("100")
                .vkToken("token")
                .vkSecret("secret")
                .vkConfirmationCode("confirmation")
                .status(VkGroupStatus.ACTIVE)
                .build());
    }
}
