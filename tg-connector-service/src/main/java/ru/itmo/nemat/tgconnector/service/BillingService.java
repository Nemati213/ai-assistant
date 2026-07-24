package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.shared.billing.CreditPricing;
import ru.itmo.nemat.tgconnector.config.BillingPricingProperties;
import ru.itmo.nemat.tgconnector.dto.BillingChargeCommand;
import ru.itmo.nemat.tgconnector.dto.BillingResultEvent;
import ru.itmo.nemat.tgconnector.model.BillingStatus;
import ru.itmo.nemat.tgconnector.model.BillingTransaction;
import ru.itmo.nemat.tgconnector.model.BalanceReservation;
import ru.itmo.nemat.tgconnector.model.BalanceReservationStatus;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.repository.BalanceReservationRepository;
import ru.itmo.nemat.tgconnector.repository.BillingTransactionRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class BillingService {

    private final BillingTransactionRepository transactionRepository;
    private final BalanceReservationRepository reservationRepository;
    private final CuratorVkGroupRepository groupRepository;
    private final CuratorRepository curatorRepository;
    private final BillingPricingProperties pricingProperties;

    @Transactional
    public BillingResultEvent charge(BillingChargeCommand command) {
        validate(command);

        return transactionRepository.findById(command.requestId())
                .map(transaction -> replayResult(command, transaction))
                .orElseGet(() -> performCharge(command));
    }

    private BillingResultEvent performCharge(BillingChargeCommand command) {
        BalanceReservation reservation =
                reservationRepository.findByIdForUpdate(command.requestId()).orElse(null);
        CuratorVkGroup group = groupRepository.findByVkGroupId(command.vkGroupId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "VK group is not registered: " + command.vkGroupId()
                ));
        Curator curator = curatorRepository.findByIdForUpdate(group.getCurator().getId())
                .orElseThrow(() -> new IllegalArgumentException("Curator not found"));

        BigDecimal balance = balance(curator);
        BigDecimal reserved = reserved(curator);
        BigDecimal amount = expectedCredits(command);
        BillingStatus status;
        String errorMessage = null;

        if (reservation != null
                && reservation.getStatus() == BalanceReservationStatus.RESERVED
                && reservation.getExpiresAt() != null
                && !reservation.getExpiresAt().isAfter(Instant.now())) {
            releaseReservation(curator, reservation, reserved, "Balance reservation expired");
            reserved = reserved(curator);
        }

        if (!isUsableReservation(command, curator, reservation)) {
            status = BillingStatus.INSUFFICIENT_FUNDS;
            errorMessage = "Active balance reservation not found";
        } else {
            BigDecimal otherReservations = reserved.subtract(reservation.getReservedCredits());
            if (otherReservations.signum() < 0) {
                throw new IllegalStateException("Curator reserved balance is inconsistent");
            }

            BigDecimal availableForCharge = balance.subtract(otherReservations);
            if (availableForCharge.compareTo(amount) >= 0) {
                balance = balance.subtract(amount);
                curator.setBalanceTokens(balance);
                curator.setReservedTokens(otherReservations);
                reservation.capture(amount, Instant.now());
                status = BillingStatus.CHARGED;
            } else {
                curator.setReservedTokens(otherReservations);
                reservation.release(
                        BalanceReservationStatus.RELEASED,
                        "Final AI charge exceeds available balance",
                        Instant.now()
                );
                status = BillingStatus.INSUFFICIENT_FUNDS;
                errorMessage = "Insufficient token balance for final AI charge";
            }
        }

        if (status == BillingStatus.CHARGED) {
            reservationRepository.save(reservation);
        }

        BillingTransaction transaction = BillingTransaction.builder()
                .requestId(command.requestId())
                .curatorId(curator.getId())
                .vkGroupId(command.vkGroupId())
                .aiTokens(command.aiTokens())
                .providerCostUsd(command.providerCostUsd())
                .creditsCharged(amount)
                .creditsPerUsd(pricingProperties.getCreditsPerUsd())
                .minimumCharge(pricingProperties.getMinimumCharge())
                .status(status)
                .balanceAfter(balance)
                .errorMessage(errorMessage)
                .createdAt(Instant.now())
                .publishAttempts(0)
                .nextPublishAttemptAt(Instant.now())
                .build();
        transactionRepository.save(transaction);

        return toResult(transaction);
    }

    private boolean isUsableReservation(
            BillingChargeCommand command,
            Curator curator,
            BalanceReservation reservation
    ) {
        return reservation != null
                && reservation.getStatus() == BalanceReservationStatus.RESERVED
                && reservation.getCuratorId().equals(curator.getId())
                && reservation.getVkGroupId().equals(command.vkGroupId())
                && reservation.getExpiresAt() != null
                && reservation.getExpiresAt().isAfter(Instant.now());
    }

    private void releaseReservation(
            Curator curator,
            BalanceReservation reservation,
            BigDecimal reserved,
            String reason
    ) {
        BigDecimal reservedAfter = reserved.subtract(reservation.getReservedCredits());
        if (reservedAfter.signum() < 0) {
            throw new IllegalStateException("Curator reserved balance is inconsistent");
        }
        curator.setReservedTokens(reservedAfter);
        reservation.release(BalanceReservationStatus.EXPIRED, reason, Instant.now());
    }

    private BigDecimal balance(Curator curator) {
        return curator.getBalanceTokens() == null
                ? BigDecimal.ZERO
                : curator.getBalanceTokens();
    }

    private BigDecimal reserved(Curator curator) {
        return curator.getReservedTokens() == null
                ? BigDecimal.ZERO
                : curator.getReservedTokens();
    }

    private BillingResultEvent toResult(BillingTransaction transaction) {
        boolean charged = transaction.getStatus() == BillingStatus.CHARGED;
        return new BillingResultEvent(
                transaction.getRequestId(),
                transaction.getStatus().name(),
                charged ? transaction.getCreditsCharged() : BigDecimal.ZERO,
                transaction.getBalanceAfter(),
                transaction.getErrorMessage()
        );
    }

    private BillingResultEvent replayResult(
            BillingChargeCommand command,
            BillingTransaction transaction
    ) {
        if (!transaction.getVkGroupId().equals(command.vkGroupId())
                || transaction.getAiTokens() != command.aiTokens()
                || transaction.getProviderCostUsd().compareTo(command.providerCostUsd()) != 0
                || transaction.getCreditsCharged().compareTo(command.creditsToCharge()) != 0) {
            throw new IllegalStateException(
                    "Billing requestId was reused with different charge parameters"
            );
        }
        return toResult(transaction);
    }

    private void validate(BillingChargeCommand command) {
        if (command.requestId() == null) {
            throw new IllegalArgumentException("requestId is required");
        }
        if (command.vkGroupId() == null || command.vkGroupId().isBlank()) {
            throw new IllegalArgumentException("vkGroupId is required");
        }
        if (command.aiTokens() < 0) {
            throw new IllegalArgumentException("AI tokens must not be negative");
        }
        if (command.providerCostUsd() == null || command.providerCostUsd().signum() < 0) {
            throw new IllegalArgumentException("Provider cost must not be negative");
        }
        if (command.creditsToCharge() == null || command.creditsToCharge().signum() < 0) {
            throw new IllegalArgumentException("Credits to charge must not be negative");
        }
        if (command.creditsPerUsd() == null
                || command.creditsPerUsd().compareTo(pricingProperties.getCreditsPerUsd()) != 0
                || command.minimumCharge() == null
                || command.minimumCharge().compareTo(pricingProperties.getMinimumCharge()) != 0) {
            throw new IllegalStateException("Billing pricing configuration mismatch");
        }
    }

    private BigDecimal expectedCredits(BillingChargeCommand command) {
        BigDecimal expected = CreditPricing.calculate(
                command.providerCostUsd(),
                pricingProperties.getCreditsPerUsd(),
                pricingProperties.getMinimumCharge()
        );
        if (expected.compareTo(command.creditsToCharge()) != 0) {
            throw new IllegalStateException(
                    "Invalid credit charge: expected " + expected
                            + ", received " + command.creditsToCharge()
            );
        }
        return expected;
    }
}
