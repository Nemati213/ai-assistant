package ru.itmo.nemat.tgconnector.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.tgconnector.dto.BillingRefundCommand;
import ru.itmo.nemat.tgconnector.dto.BillingRefundResultEvent;
import ru.itmo.nemat.tgconnector.model.BillingRefund;
import ru.itmo.nemat.tgconnector.model.BillingRefundStatus;
import ru.itmo.nemat.tgconnector.model.BillingStatus;
import ru.itmo.nemat.tgconnector.model.BillingTransaction;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.repository.BillingRefundRepository;
import ru.itmo.nemat.tgconnector.repository.BillingTransactionRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingRefundServiceTest {

    @Mock
    private BillingRefundRepository refundRepository;
    @Mock
    private BillingTransactionRepository transactionRepository;
    @Mock
    private CuratorRepository curatorRepository;

    private BillingRefundService refundService;

    @BeforeEach
    void setUp() {
        refundService = new BillingRefundService(
                refundRepository,
                transactionRepository,
                curatorRepository
        );
    }

    @Test
    void returnsChargedCreditsToCurator() {
        UUID requestId = UUID.randomUUID();
        Curator curator = curator(new BigDecimal("50"));
        BillingTransaction transaction = chargedTransaction(requestId, curator.getId());
        when(refundRepository.findById(requestId)).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(transaction));
        when(curatorRepository.findByIdForUpdate(curator.getId()))
                .thenReturn(Optional.of(curator));

        BillingRefundResultEvent result =
                refundService.refund(new BillingRefundCommand(requestId, "VK API error"));

        assertThat(result.status()).isEqualTo("REFUNDED");
        assertThat(result.refundedCredits()).isEqualByComparingTo("100");
        assertThat(result.balanceAfter()).isEqualByComparingTo("150");
        assertThat(curator.getBalanceTokens()).isEqualByComparingTo("150");

        ArgumentCaptor<BillingRefund> captor = ArgumentCaptor.forClass(BillingRefund.class);
        verify(refundRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BillingRefundStatus.REFUNDED);
    }

    @Test
    void repeatedCommandDoesNotReturnCreditsTwice() {
        UUID requestId = UUID.randomUUID();
        BillingRefund existing = BillingRefund.builder()
                .requestId(requestId)
                .curatorId(UUID.randomUUID())
                .credits(new BigDecimal("100"))
                .status(BillingRefundStatus.REFUNDED)
                .balanceAfter(new BigDecimal("150"))
                .createdAt(Instant.now())
                .publishAttempts(0)
                .nextPublishAttemptAt(Instant.now())
                .build();
        when(refundRepository.findById(requestId)).thenReturn(Optional.of(existing));

        BillingRefundResultEvent result =
                refundService.refund(new BillingRefundCommand(requestId, "Repeated command"));

        assertThat(result.status()).isEqualTo("REFUNDED");
        assertThat(result.balanceAfter()).isEqualByComparingTo("150");
        verify(transactionRepository, never()).findByIdForUpdate(any());
        verify(curatorRepository, never()).findByIdForUpdate(any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    void rejectsRefundWhenOriginalChargeDoesNotExist() {
        UUID requestId = UUID.randomUUID();
        when(refundRepository.findById(requestId)).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(requestId)).thenReturn(Optional.empty());

        BillingRefundResultEvent result =
                refundService.refund(new BillingRefundCommand(requestId, "VK API error"));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.refundedCredits()).isZero();
        assertThat(result.errorMessage()).contains("not found");
        verify(curatorRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void rejectsRefundForTransactionWithoutCharge() {
        UUID requestId = UUID.randomUUID();
        BillingTransaction transaction = chargedTransaction(requestId, UUID.randomUUID());
        transaction = BillingTransaction.builder()
                .requestId(transaction.getRequestId())
                .curatorId(transaction.getCuratorId())
                .vkGroupId(transaction.getVkGroupId())
                .aiTokens(transaction.getAiTokens())
                .providerCostUsd(transaction.getProviderCostUsd())
                .creditsCharged(BigDecimal.ZERO)
                .creditsPerUsd(transaction.getCreditsPerUsd())
                .minimumCharge(transaction.getMinimumCharge())
                .status(BillingStatus.INSUFFICIENT_FUNDS)
                .balanceAfter(new BigDecimal("50"))
                .createdAt(Instant.now())
                .publishAttempts(0)
                .nextPublishAttemptAt(Instant.now())
                .build();
        when(refundRepository.findById(requestId)).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(transaction));

        BillingRefundResultEvent result =
                refundService.refund(new BillingRefundCommand(requestId, "VK API error"));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.refundedCredits()).isZero();
        verify(curatorRepository, never()).findByIdForUpdate(any());
    }

    private BillingTransaction chargedTransaction(UUID requestId, UUID curatorId) {
        Instant now = Instant.now();
        return BillingTransaction.builder()
                .requestId(requestId)
                .curatorId(curatorId)
                .vkGroupId("100")
                .aiTokens(30)
                .providerCostUsd(new BigDecimal("0.00015"))
                .creditsCharged(new BigDecimal("100"))
                .creditsPerUsd(new BigDecimal("200000"))
                .minimumCharge(new BigDecimal("100"))
                .status(BillingStatus.CHARGED)
                .balanceAfter(new BigDecimal("50"))
                .createdAt(now)
                .publishAttempts(0)
                .nextPublishAttemptAt(now)
                .build();
    }

    private Curator curator(BigDecimal balance) {
        return Curator.builder()
                .id(UUID.randomUUID())
                .tgChatId(123L)
                .balanceTokens(balance)
                .build();
    }
}
