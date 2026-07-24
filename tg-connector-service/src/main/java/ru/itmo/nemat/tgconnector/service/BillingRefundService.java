package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

@Service
@RequiredArgsConstructor
public class BillingRefundService {

    private static final int MAX_REASON_LENGTH = 1000;

    private final BillingRefundRepository refundRepository;
    private final BillingTransactionRepository transactionRepository;
    private final CuratorRepository curatorRepository;

    @Transactional
    public BillingRefundResultEvent refund(BillingRefundCommand command) {
        validate(command);

        BillingRefund existing = refundRepository.findById(command.requestId()).orElse(null);
        if (existing != null) {
            return toResult(existing);
        }

        BillingTransaction transaction =
                transactionRepository.findByIdForUpdate(command.requestId()).orElse(null);
        if (transaction == null) {
            return saveRejected(command, "Original billing transaction not found");
        }

        existing = refundRepository.findById(command.requestId()).orElse(null);
        if (existing != null) {
            return toResult(existing);
        }

        if (transaction.getStatus() != BillingStatus.CHARGED) {
            return saveRejected(command, "Original billing transaction was not charged");
        }

        Curator curator = curatorRepository.findByIdForUpdate(transaction.getCuratorId())
                .orElseThrow(() -> new IllegalArgumentException("Curator not found"));
        BigDecimal balance = curator.getBalanceTokens() == null
                ? BigDecimal.ZERO
                : curator.getBalanceTokens();
        BigDecimal balanceAfter = balance.add(transaction.getCreditsCharged());
        curator.setBalanceTokens(balanceAfter);

        BillingRefund refund = BillingRefund.builder()
                .requestId(command.requestId())
                .curatorId(curator.getId())
                .credits(transaction.getCreditsCharged())
                .status(BillingRefundStatus.REFUNDED)
                .balanceAfter(balanceAfter)
                .reason(normalizeReason(command.reason()))
                .createdAt(Instant.now())
                .publishAttempts(0)
                .nextPublishAttemptAt(Instant.now())
                .build();
        refundRepository.save(refund);
        return toResult(refund);
    }

    private BillingRefundResultEvent saveRejected(
            BillingRefundCommand command,
            String errorMessage
    ) {
        BillingRefund refund = BillingRefund.builder()
                .requestId(command.requestId())
                .credits(BigDecimal.ZERO)
                .status(BillingRefundStatus.REJECTED)
                .reason(normalizeReason(command.reason()))
                .errorMessage(errorMessage)
                .createdAt(Instant.now())
                .publishAttempts(0)
                .nextPublishAttemptAt(Instant.now())
                .build();
        refundRepository.save(refund);
        return toResult(refund);
    }

    private BillingRefundResultEvent toResult(BillingRefund refund) {
        return new BillingRefundResultEvent(
                refund.getRequestId(),
                refund.getStatus().name(),
                refund.getCredits(),
                refund.getBalanceAfter(),
                refund.getErrorMessage()
        );
    }

    private void validate(BillingRefundCommand command) {
        if (command.requestId() == null) {
            throw new IllegalArgumentException("requestId is required");
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "VK delivery failed";
        }
        return reason.length() <= MAX_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_REASON_LENGTH);
    }
}
