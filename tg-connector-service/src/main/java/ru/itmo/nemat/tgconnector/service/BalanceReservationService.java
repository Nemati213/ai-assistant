package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.dto.BalanceReleaseCommand;
import ru.itmo.nemat.tgconnector.dto.BalanceReservationCommand;
import ru.itmo.nemat.tgconnector.dto.BalanceReservationResultEvent;
import ru.itmo.nemat.tgconnector.model.BalanceReservation;
import ru.itmo.nemat.tgconnector.model.BalanceReservationStatus;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.model.VkGroupStatus;
import ru.itmo.nemat.tgconnector.repository.BalanceReservationRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BalanceReservationService {

    private final BalanceReservationRepository reservationRepository;
    private final CuratorVkGroupRepository groupRepository;
    private final CuratorRepository curatorRepository;

    @Value("${app.billing.reservations.expiration-batch-size:100}")
    private int expirationBatchSize;

    @Transactional
    public BalanceReservationResultEvent reserve(BalanceReservationCommand command) {
        validate(command);

        BalanceReservation existing =
                reservationRepository.findByIdForUpdate(command.requestId()).orElse(null);
        if (existing != null) {
            return replay(command, existing);
        }

        if (!command.expiresAt().isAfter(Instant.now())) {
            return reject(
                    command,
                    null,
                    BalanceReservationStatus.EXPIRED,
                    "Reservation request expired before processing"
            );
        }

        CuratorVkGroup group = groupRepository.findByVkGroupId(command.vkGroupId())
                .orElse(null);
        if (group == null || group.getStatus() != VkGroupStatus.ACTIVE) {
            return reject(
                    command,
                    group == null ? null : group.getCurator().getId(),
                    BalanceReservationStatus.GROUP_UNAVAILABLE,
                    "VK group is not active"
            );
        }

        Curator curator = curatorRepository.findByIdForUpdate(group.getCurator().getId())
                .orElseThrow(() -> new IllegalArgumentException("Curator not found"));

        existing = reservationRepository.findByIdForUpdate(command.requestId()).orElse(null);
        if (existing != null) {
            return replay(command, existing);
        }

        BigDecimal balance = balance(curator);
        BigDecimal reserved = reserved(curator);
        BigDecimal available = balance.subtract(reserved);
        if (available.compareTo(command.reservedCredits()) < 0) {
            return reject(
                    command,
                    curator.getId(),
                    BalanceReservationStatus.INSUFFICIENT_FUNDS,
                    "Insufficient available balance for AI generation",
                    balance,
                    available
            );
        }

        BigDecimal availableAfter = available.subtract(command.reservedCredits());
        curator.setReservedTokens(reserved.add(command.reservedCredits()));
        BalanceReservation reservation = BalanceReservation.builder()
                .requestId(command.requestId())
                .curatorId(curator.getId())
                .vkGroupId(command.vkGroupId())
                .reservedCredits(command.reservedCredits())
                .status(BalanceReservationStatus.RESERVED)
                .balanceAtReservation(balance)
                .availableBalanceAfter(availableAfter)
                .createdAt(Instant.now())
                .expiresAt(command.expiresAt())
                .build();
        reservationRepository.save(reservation);
        return toResult(reservation);
    }

    @Transactional
    public void release(BalanceReleaseCommand command) {
        if (command.requestId() == null) {
            throw new IllegalArgumentException("requestId is required");
        }

        BalanceReservation reservation =
                reservationRepository.findByIdForUpdate(command.requestId()).orElse(null);
        if (reservation == null || reservation.getStatus() != BalanceReservationStatus.RESERVED) {
            return;
        }

        Curator curator = curatorRepository.findByIdForUpdate(reservation.getCuratorId())
                .orElseThrow(() -> new IllegalArgumentException("Curator not found"));
        releaseHeldCredits(curator, reservation);
        reservation.release(
                BalanceReservationStatus.RELEASED,
                normalizeReason(command.reason()),
                Instant.now()
        );
    }

    @Scheduled(fixedDelayString = "${app.billing.reservations.expiration-poll-ms:60000}")
    @Transactional
    public void expireReservations() {
        Instant now = Instant.now();
        List<BalanceReservation> expired =
                reservationRepository.findExpiredForUpdate(now, expirationBatchSize);
        for (BalanceReservation reservation : expired) {
            Curator curator = curatorRepository.findByIdForUpdate(reservation.getCuratorId())
                    .orElseThrow(() -> new IllegalArgumentException("Curator not found"));
            releaseHeldCredits(curator, reservation);
            reservation.release(
                    BalanceReservationStatus.EXPIRED,
                    "Reservation expired",
                    now
            );
            log.info("[{}] Balance reservation expired", reservation.getRequestId());
        }
    }

    private BalanceReservationResultEvent reject(
            BalanceReservationCommand command,
            java.util.UUID curatorId,
            BalanceReservationStatus status,
            String errorMessage
    ) {
        return reject(
                command,
                curatorId,
                status,
                errorMessage,
                null,
                null
        );
    }

    private BalanceReservationResultEvent reject(
            BalanceReservationCommand command,
            java.util.UUID curatorId,
            BalanceReservationStatus status,
            String errorMessage,
            BigDecimal balance,
            BigDecimal available
    ) {
        BalanceReservation reservation = BalanceReservation.builder()
                .requestId(command.requestId())
                .curatorId(curatorId)
                .vkGroupId(command.vkGroupId())
                .reservedCredits(command.reservedCredits())
                .status(status)
                .balanceAtReservation(balance)
                .availableBalanceAfter(available)
                .errorMessage(errorMessage)
                .createdAt(Instant.now())
                .expiresAt(command.expiresAt())
                .completedAt(Instant.now())
                .build();
        reservationRepository.save(reservation);
        return toResult(reservation);
    }

    private BalanceReservationResultEvent replay(
            BalanceReservationCommand command,
            BalanceReservation reservation
    ) {
        if (!reservation.getVkGroupId().equals(command.vkGroupId())
                || reservation.getReservedCredits().compareTo(command.reservedCredits()) != 0
                || !java.util.Objects.equals(reservation.getExpiresAt(), command.expiresAt())) {
            throw new IllegalStateException(
                    "Reservation requestId was reused with different parameters"
            );
        }
        return toResult(reservation);
    }

    private BalanceReservationResultEvent toResult(BalanceReservation reservation) {
        return new BalanceReservationResultEvent(
                reservation.getRequestId(),
                reservation.getStatus().name(),
                reservation.getBalanceAtReservation(),
                reservation.getAvailableBalanceAfter(),
                reservation.getReservedCredits(),
                reservation.getExpiresAt(),
                reservation.getErrorMessage()
        );
    }

    private void releaseHeldCredits(Curator curator, BalanceReservation reservation) {
        BigDecimal reservedAfter = reserved(curator).subtract(reservation.getReservedCredits());
        if (reservedAfter.signum() < 0) {
            throw new IllegalStateException("Curator reserved balance is inconsistent");
        }
        curator.setReservedTokens(reservedAfter);
    }

    private BigDecimal balance(Curator curator) {
        return curator.getBalanceTokens() == null ? BigDecimal.ZERO : curator.getBalanceTokens();
    }

    private BigDecimal reserved(Curator curator) {
        return curator.getReservedTokens() == null ? BigDecimal.ZERO : curator.getReservedTokens();
    }

    private void validate(BalanceReservationCommand command) {
        if (command.requestId() == null) {
            throw new IllegalArgumentException("requestId is required");
        }
        if (command.vkGroupId() == null || command.vkGroupId().isBlank()) {
            throw new IllegalArgumentException("vkGroupId is required");
        }
        if (command.reservedCredits() == null || command.reservedCredits().signum() <= 0) {
            throw new IllegalArgumentException("reservedCredits must be positive");
        }
        if (command.expiresAt() == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Reservation released";
        }
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000);
    }
}
