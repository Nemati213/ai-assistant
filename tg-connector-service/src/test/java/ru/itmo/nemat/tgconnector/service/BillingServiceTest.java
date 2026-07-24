package ru.itmo.nemat.tgconnector.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.tgconnector.config.BillingPricingProperties;
import ru.itmo.nemat.tgconnector.dto.BillingChargeCommand;
import ru.itmo.nemat.tgconnector.dto.BillingResultEvent;
import ru.itmo.nemat.tgconnector.model.BillingStatus;
import ru.itmo.nemat.tgconnector.model.BillingTransaction;
import ru.itmo.nemat.tgconnector.model.BalanceReservation;
import ru.itmo.nemat.tgconnector.model.BalanceReservationStatus;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.repository.BillingTransactionRepository;
import ru.itmo.nemat.tgconnector.repository.BalanceReservationRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock
    private BillingTransactionRepository transactionRepository;
    @Mock
    private BalanceReservationRepository reservationRepository;
    @Mock
    private CuratorVkGroupRepository groupRepository;
    @Mock
    private CuratorRepository curatorRepository;

    private BillingService billingService;

    @BeforeEach
    void setUp() {
        billingService = new BillingService(
                transactionRepository,
                reservationRepository,
                groupRepository,
                curatorRepository,
                new BillingPricingProperties()
        );
    }

    @Test
    void deductsTokensAndStoresTransaction() {
        UUID requestId = UUID.randomUUID();
        Curator curator = curator(new BigDecimal("100"));
        arrangeNewCharge(requestId, curator);

        BillingResultEvent result =
                billingService.charge(command(requestId));

        assertThat(result.status()).isEqualTo("CHARGED");
        assertThat(result.chargedCredits()).isEqualByComparingTo("100");
        assertThat(result.balanceAfter()).isEqualByComparingTo("0");
        assertThat(curator.getBalanceTokens()).isEqualByComparingTo("0");
        assertThat(curator.getReservedTokens()).isZero();

        ArgumentCaptor<BillingTransaction> captor =
                ArgumentCaptor.forClass(BillingTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BillingStatus.CHARGED);
    }

    @Test
    void keepsBalanceWhenFundsAreInsufficient() {
        UUID requestId = UUID.randomUUID();
        Curator curator = curator(new BigDecimal("100"));
        arrangeNewCharge(requestId, curator);

        BillingResultEvent result =
                billingService.charge(expensiveCommand(requestId));

        assertThat(result.status()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(result.chargedCredits()).isZero();
        assertThat(result.balanceAfter()).isEqualByComparingTo("100");
        assertThat(curator.getBalanceTokens()).isEqualByComparingTo("100");
        assertThat(curator.getReservedTokens()).isZero();
    }

    @Test
    void repeatedCommandDoesNotDeductBalanceAgain() {
        UUID requestId = UUID.randomUUID();
        BillingTransaction existing = BillingTransaction.builder()
                .requestId(requestId)
                .curatorId(UUID.randomUUID())
                .vkGroupId("100")
                .aiTokens(30)
                .providerCostUsd(new BigDecimal("0.00015"))
                .creditsCharged(new BigDecimal("100"))
                .creditsPerUsd(new BigDecimal("200000"))
                .minimumCharge(new BigDecimal("100"))
                .status(BillingStatus.CHARGED)
                .balanceAfter(BigDecimal.ZERO)
                .createdAt(Instant.now())
                .build();
        when(transactionRepository.findById(requestId)).thenReturn(Optional.of(existing));

        BillingResultEvent result =
                billingService.charge(command(requestId));

        assertThat(result.status()).isEqualTo("CHARGED");
        assertThat(result.balanceAfter()).isEqualByComparingTo("0");
        verify(groupRepository, never()).findByVkGroupId(any());
        verify(reservationRepository, never()).findByIdForUpdate(any());
        verify(curatorRepository, never()).findByIdForUpdate(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void rejectsReusedRequestIdWithDifferentAmount() {
        UUID requestId = UUID.randomUUID();
        BillingTransaction existing = BillingTransaction.builder()
                .requestId(requestId)
                .curatorId(UUID.randomUUID())
                .vkGroupId("100")
                .aiTokens(30)
                .providerCostUsd(new BigDecimal("0.00015"))
                .creditsCharged(new BigDecimal("100"))
                .creditsPerUsd(new BigDecimal("200000"))
                .minimumCharge(new BigDecimal("100"))
                .status(BillingStatus.CHARGED)
                .balanceAfter(BigDecimal.ZERO)
                .createdAt(Instant.now())
                .build();
        when(transactionRepository.findById(requestId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->
                billingService.charge(new BillingChargeCommand(
                        requestId,
                        "100",
                        31,
                        new BigDecimal("0.00015"),
                        new BigDecimal("100"),
                        new BigDecimal("200000"),
                        new BigDecimal("100")
                ))
        ).isInstanceOf(IllegalStateException.class);
    }

    private void arrangeNewCharge(UUID requestId, Curator curator) {
        CuratorVkGroup group = CuratorVkGroup.builder()
                .id(UUID.randomUUID())
                .curator(curator)
                .vkGroupId("100")
                .build();
        BalanceReservation reservation = BalanceReservation.builder()
                .requestId(requestId)
                .curatorId(curator.getId())
                .vkGroupId("100")
                .reservedCredits(new BigDecimal("100"))
                .status(BalanceReservationStatus.RESERVED)
                .createdAt(Instant.now())
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .build();
        when(transactionRepository.findById(requestId)).thenReturn(Optional.empty());
        when(reservationRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(reservation));
        when(groupRepository.findByVkGroupId("100")).thenReturn(Optional.of(group));
        when(curatorRepository.findByIdForUpdate(curator.getId())).thenReturn(Optional.of(curator));
    }

    private Curator curator(BigDecimal balance) {
        return Curator.builder()
                .id(UUID.randomUUID())
                .tgChatId(123L)
                .balanceTokens(balance)
                .reservedTokens(new BigDecimal("100"))
                .build();
    }

    private BillingChargeCommand command(UUID requestId) {
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

    private BillingChargeCommand expensiveCommand(UUID requestId) {
        return new BillingChargeCommand(
                requestId,
                "100",
                300,
                new BigDecimal("0.001"),
                new BigDecimal("200"),
                new BigDecimal("200000"),
                new BigDecimal("100")
        );
    }
}
