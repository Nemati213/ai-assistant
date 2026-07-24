package ru.itmo.nemat.tgconnector.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "balance_reservations")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceReservation {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "curator_id")
    private UUID curatorId;

    @Column(name = "vk_group_id", nullable = false)
    private String vkGroupId;

    @Column(name = "reserved_credits", nullable = false, precision = 38, scale = 2)
    private BigDecimal reservedCredits;

    @Column(name = "actual_credits", precision = 38, scale = 2)
    private BigDecimal actualCredits;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BalanceReservationStatus status;

    @Column(name = "balance_at_reservation", precision = 38, scale = 2)
    private BigDecimal balanceAtReservation;

    @Column(name = "available_balance_after", precision = 38, scale = 2)
    private BigDecimal availableBalanceAfter;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public void capture(BigDecimal actualCredits, Instant completedAt) {
        this.actualCredits = actualCredits;
        this.status = BalanceReservationStatus.CAPTURED;
        this.completedAt = completedAt;
        this.errorMessage = null;
    }

    public void release(BalanceReservationStatus targetStatus, String reason, Instant completedAt) {
        this.status = targetStatus;
        this.errorMessage = reason;
        this.completedAt = completedAt;
    }
}
