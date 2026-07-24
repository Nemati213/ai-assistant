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
@Table(name = "balance_credit_transactions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceCreditTransaction {

    @Id
    private UUID id;

    @Column(name = "curator_id", nullable = false)
    private UUID curatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private BalanceCreditSource source;

    @Column(name = "external_id", nullable = false, unique = true)
    private String externalId;

    @Column(name = "credits", nullable = false)
    private BigDecimal credits;

    @Column(name = "stars_amount", nullable = false)
    private int starsAmount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "invoice_payload", nullable = false)
    private String invoicePayload;

    @Column(name = "balance_after", nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
