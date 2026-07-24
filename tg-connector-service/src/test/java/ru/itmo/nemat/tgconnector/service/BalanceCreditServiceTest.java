package ru.itmo.nemat.tgconnector.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.tgconnector.config.TelegramStarsProperties;
import ru.itmo.nemat.tgconnector.model.BalanceCreditTransaction;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.repository.BalanceCreditTransactionRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceCreditServiceTest {

    @Mock
    private BalanceCreditTransactionRepository transactionRepository;
    @Mock
    private CuratorRepository curatorRepository;

    private BalanceCreditService service;
    private TelegramStarsProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TelegramStarsProperties();
        service = new BalanceCreditService(
                transactionRepository,
                curatorRepository,
                properties
        );
    }

    @Test
    void creditsProPackageAfterSuccessfulStarsPayment() {
        Curator curator = curator(new BigDecimal("10000"));
        when(curatorRepository.findByTgChatIdForUpdate(123L))
                .thenReturn(Optional.of(curator));
        when(transactionRepository.findByExternalId("charge-1"))
                .thenReturn(Optional.empty());

        BalanceCreditService.CreditResult result = service.creditTelegramStars(
                123L,
                "XTR",
                350,
                "pro-350-stars-v1",
                "charge-1"
        );

        assertThat(result.newlyCredited()).isTrue();
        assertThat(result.credited()).isEqualByComparingTo("300000");
        assertThat(result.balanceAfter()).isEqualByComparingTo("310000");
        assertThat(curator.getBalanceTokens()).isEqualByComparingTo("310000");

        ArgumentCaptor<BalanceCreditTransaction> captor =
                ArgumentCaptor.forClass(BalanceCreditTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getExternalId()).isEqualTo("charge-1");
        assertThat(captor.getValue().getStarsAmount()).isEqualTo(350);
    }

    @Test
    void duplicatePaymentDoesNotCreditBalanceTwice() {
        Curator curator = curator(new BigDecimal("310000"));
        BalanceCreditTransaction existing = BalanceCreditTransaction.builder()
                .id(UUID.randomUUID())
                .curatorId(curator.getId())
                .externalId("charge-1")
                .credits(new BigDecimal("300000"))
                .starsAmount(350)
                .currency("XTR")
                .invoicePayload("pro-350-stars-v1")
                .balanceAfter(new BigDecimal("310000"))
                .createdAt(Instant.now())
                .build();
        when(curatorRepository.findByTgChatIdForUpdate(123L))
                .thenReturn(Optional.of(curator));
        when(transactionRepository.findByExternalId("charge-1"))
                .thenReturn(Optional.of(existing));

        BalanceCreditService.CreditResult result = service.creditTelegramStars(
                123L,
                "XTR",
                350,
                "pro-350-stars-v1",
                "charge-1"
        );

        assertThat(result.newlyCredited()).isFalse();
        assertThat(curator.getBalanceTokens()).isEqualByComparingTo("310000");
        verify(transactionRepository, never()).save(
                org.mockito.ArgumentMatchers.any(BalanceCreditTransaction.class)
        );
    }

    @Test
    void rejectsUnexpectedStarsAmount() {
        assertThatThrownBy(() -> service.creditTelegramStars(
                123L,
                "XTR",
                349,
                "pro-350-stars-v1",
                "charge-1"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReusedChargeIdForAnotherCurator() {
        Curator curator = curator(new BigDecimal("10000"));
        BalanceCreditTransaction existing = BalanceCreditTransaction.builder()
                .id(UUID.randomUUID())
                .curatorId(UUID.randomUUID())
                .externalId("charge-1")
                .credits(new BigDecimal("300000"))
                .starsAmount(350)
                .currency("XTR")
                .invoicePayload("pro-350-stars-v1")
                .balanceAfter(new BigDecimal("300000"))
                .createdAt(Instant.now())
                .build();
        when(curatorRepository.findByTgChatIdForUpdate(123L))
                .thenReturn(Optional.of(curator));
        when(transactionRepository.findByExternalId("charge-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.creditTelegramStars(
                123L,
                "XTR",
                350,
                "pro-350-stars-v1",
                "charge-1"
        )).isInstanceOf(IllegalStateException.class);
    }

    private Curator curator(BigDecimal balance) {
        return Curator.builder()
                .id(UUID.randomUUID())
                .tgChatId(123L)
                .balanceTokens(balance)
                .build();
    }
}
