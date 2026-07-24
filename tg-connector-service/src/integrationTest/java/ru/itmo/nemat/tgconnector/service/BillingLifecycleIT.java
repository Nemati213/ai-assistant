package ru.itmo.nemat.tgconnector.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import ru.itmo.nemat.tgconnector.config.BillingPricingProperties;
import ru.itmo.nemat.tgconnector.config.SecretEncryptionConfig;
import ru.itmo.nemat.tgconnector.config.TelegramStarsProperties;
import ru.itmo.nemat.tgconnector.dto.BillingChargeCommand;
import ru.itmo.nemat.tgconnector.dto.BillingRefundCommand;
import ru.itmo.nemat.tgconnector.dto.BillingRefundResultEvent;
import ru.itmo.nemat.tgconnector.dto.BillingResultEvent;
import ru.itmo.nemat.tgconnector.persistence.EncryptedStringConverter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "app.security.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        BillingService.class,
        BillingRefundService.class,
        BalanceCreditService.class,
        BillingPricingProperties.class,
        TelegramStarsProperties.class,
        SecretEncryptionConfig.class,
        EncryptedStringConverter.class
})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BillingLifecycleIT {

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
    private BillingService billingService;
    @Autowired
    private BillingRefundService refundService;
    @Autowired
    private BalanceCreditService creditService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM billing_refunds");
        jdbcTemplate.update("DELETE FROM billing_transactions");
        jdbcTemplate.update("DELETE FROM balance_credit_transactions");
        jdbcTemplate.update("DELETE FROM balance_reservations");
        jdbcTemplate.update("DELETE FROM curator_vk_groups");
        jdbcTemplate.update("DELETE FROM curators");
        jdbcTemplate.update("DELETE FROM subjects");
    }

    @AfterEach
    void stopExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void chargeAndRefundRemainIdempotentAcrossCommittedTransactions() {
        Fixture fixture = seedBillingFixture(new BigDecimal("500"));
        BillingChargeCommand chargeCommand = chargeCommand(fixture.requestId());

        BillingResultEvent firstCharge = billingService.charge(chargeCommand);
        BillingResultEvent replayedCharge = billingService.charge(chargeCommand);
        BillingRefundCommand refundCommand =
                new BillingRefundCommand(fixture.requestId(), "VK delivery failed");
        BillingRefundResultEvent firstRefund = refundService.refund(refundCommand);
        BillingRefundResultEvent replayedRefund = refundService.refund(refundCommand);

        assertThat(firstCharge.status()).isEqualTo("CHARGED");
        assertThat(replayedCharge.status()).isEqualTo(firstCharge.status());
        assertThat(replayedCharge.chargedCredits())
                .isEqualByComparingTo(firstCharge.chargedCredits());
        assertThat(replayedCharge.balanceAfter())
                .isEqualByComparingTo(firstCharge.balanceAfter());
        assertThat(firstRefund.status()).isEqualTo("REFUNDED");
        assertThat(replayedRefund.status()).isEqualTo(firstRefund.status());
        assertThat(replayedRefund.refundedCredits())
                .isEqualByComparingTo(firstRefund.refundedCredits());
        assertThat(replayedRefund.balanceAfter())
                .isEqualByComparingTo(firstRefund.balanceAfter());
        assertThat(balance(fixture.curatorId())).isEqualByComparingTo("500");
        assertThat(reservedBalance(fixture.curatorId())).isZero();
        assertThat(count("billing_transactions")).isEqualTo(1);
        assertThat(count("billing_refunds")).isEqualTo(1);
        assertThat(reservationStatus(fixture.requestId())).isEqualTo("CAPTURED");
    }

    @Test
    void concurrentDuplicateChargeIsAppliedExactlyOnce() throws Exception {
        Fixture fixture = seedBillingFixture(new BigDecimal("500"));
        BillingChargeCommand command = chargeCommand(fixture.requestId());
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<BillingResultEvent> first = executor.submit(() -> {
            start.await();
            return billingService.charge(command);
        });
        Future<BillingResultEvent> second = executor.submit(() -> {
            start.await();
            return billingService.charge(command);
        });
        start.countDown();

        List<BillingResultEvent> results = List.of(
                first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS)
        );

        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo("CHARGED");
            assertThat(result.chargedCredits()).isEqualByComparingTo("100");
            assertThat(result.balanceAfter()).isEqualByComparingTo("400");
        });
        assertThat(balance(fixture.curatorId())).isEqualByComparingTo("400");
        assertThat(reservedBalance(fixture.curatorId())).isZero();
        assertThat(count("billing_transactions")).isEqualTo(1);
    }

    @Test
    void invalidChargeRollsBackBalanceAndReservationChanges() {
        Fixture fixture = seedBillingFixture(new BigDecimal("500"));
        BillingChargeCommand invalidCommand = new BillingChargeCommand(
                fixture.requestId(),
                "100",
                30,
                new BigDecimal("0.00015"),
                new BigDecimal("101"),
                new BigDecimal("200000"),
                new BigDecimal("100")
        );

        assertThatThrownBy(() -> billingService.charge(invalidCommand))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid credit charge");

        assertThat(balance(fixture.curatorId())).isEqualByComparingTo("500");
        assertThat(reservedBalance(fixture.curatorId())).isEqualByComparingTo("100");
        assertThat(reservationStatus(fixture.requestId())).isEqualTo("RESERVED");
        assertThat(count("billing_transactions")).isZero();
    }

    @Test
    void concurrentTelegramPaymentIsCreditedExactlyOnce() throws Exception {
        Fixture fixture = seedCurator(new BigDecimal("500"));
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<BalanceCreditService.CreditResult> first = executor.submit(() -> {
            start.await();
            return creditTelegramPayment();
        });
        Future<BalanceCreditService.CreditResult> second = executor.submit(() -> {
            start.await();
            return creditTelegramPayment();
        });
        start.countDown();

        List<BalanceCreditService.CreditResult> results = List.of(
                first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS)
        );

        assertThat(results).extracting(BalanceCreditService.CreditResult::newlyCredited)
                .containsExactlyInAnyOrder(true, false);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.credited()).isEqualByComparingTo("300000");
            assertThat(result.balanceAfter()).isEqualByComparingTo("300500");
        });
        assertThat(balance(fixture.curatorId())).isEqualByComparingTo("300500");
        assertThat(count("balance_credit_transactions")).isEqualTo(1);
    }

    private BalanceCreditService.CreditResult creditTelegramPayment() {
        return creditService.creditTelegramStars(
                123L,
                "XTR",
                350,
                "pro-350-stars-v1",
                "telegram-charge-1"
        );
    }

    private Fixture seedBillingFixture(BigDecimal balance) {
        Fixture fixture = seedCurator(balance);
        jdbcTemplate.update("""
                INSERT INTO curator_vk_groups (
                    id, curator_id, vk_group_id, vk_token, vk_secret,
                    vk_confirmation_code, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                fixture.curatorId(),
                "100",
                "token",
                "secret",
                "confirmation",
                "ACTIVE"
        );
        jdbcTemplate.update("""
                INSERT INTO balance_reservations (
                    request_id, curator_id, vk_group_id, reserved_credits,
                    status, balance_at_reservation, available_balance_after,
                    created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                fixture.requestId(),
                fixture.curatorId(),
                "100",
                new BigDecimal("100"),
                "RESERVED",
                balance,
                balance.subtract(new BigDecimal("100")),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plusSeconds(3600))
        );
        jdbcTemplate.update(
                "UPDATE curators SET reserved_tokens = ? WHERE id = ?",
                new BigDecimal("100"),
                fixture.curatorId()
        );
        return fixture;
    }

    private Fixture seedCurator(BigDecimal balance) {
        UUID subjectId = UUID.randomUUID();
        UUID curatorId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO subjects (id, code, name, system_prompt) VALUES (?, ?, ?, ?)",
                subjectId,
                "math-" + subjectId,
                "Mathematics",
                "Answer clearly"
        );
        jdbcTemplate.update("""
                INSERT INTO curators (
                    id, tg_chat_id, username, subject_id, balance_tokens, reserved_tokens
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                curatorId,
                123L,
                "curator",
                subjectId,
                balance,
                BigDecimal.ZERO
        );
        return new Fixture(requestId, curatorId);
    }

    private BillingChargeCommand chargeCommand(UUID requestId) {
        return new BillingChargeCommand(
                requestId,
                "100",
                30,
                new BigDecimal("0.00015"),
                new BigDecimal("100"),
                new BigDecimal("200000"),
                new BigDecimal("100")
        );
    }

    private BigDecimal balance(UUID curatorId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance_tokens FROM curators WHERE id = ?",
                BigDecimal.class,
                curatorId
        );
    }

    private BigDecimal reservedBalance(UUID curatorId) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_tokens FROM curators WHERE id = ?",
                BigDecimal.class,
                curatorId
        );
    }

    private String reservationStatus(UUID requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM balance_reservations WHERE request_id = ?",
                String.class,
                requestId
        );
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record Fixture(UUID requestId, UUID curatorId) {
    }
}
