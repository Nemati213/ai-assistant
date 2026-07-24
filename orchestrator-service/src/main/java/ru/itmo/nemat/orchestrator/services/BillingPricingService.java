package ru.itmo.nemat.orchestrator.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.itmo.nemat.orchestrator.config.BillingPricingProperties;
import ru.itmo.nemat.shared.billing.CreditPricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class BillingPricingService {

    private final BillingPricingProperties properties;

    public BigDecimal calculate(BigDecimal providerCostUsd) {
        return CreditPricing.calculate(
                providerCostUsd,
                properties.getCreditsPerUsd(),
                properties.getMinimumCharge()
        );
    }

    public BigDecimal creditsPerUsd() {
        return properties.getCreditsPerUsd();
    }

    public BigDecimal minimumCharge() {
        return properties.getMinimumCharge();
    }

    public BigDecimal reservationCredits() {
        BigDecimal reservationCredits = properties.getReservationCredits();
        if (reservationCredits == null
                || reservationCredits.compareTo(properties.getMinimumCharge()) < 0) {
            throw new IllegalStateException(
                    "Reservation credits must cover the minimum billing charge"
            );
        }
        return reservationCredits;
    }

    public Instant reservationExpiresAt() {
        if (properties.getReservationTtl() == null
                || properties.getReservationTtl().isZero()
                || properties.getReservationTtl().isNegative()) {
            throw new IllegalStateException("Reservation TTL must be positive");
        }
        return Instant.now()
                .plus(properties.getReservationTtl())
                .truncatedTo(ChronoUnit.MILLIS);
    }
}
