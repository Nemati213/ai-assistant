package ru.itmo.nemat.shared.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CreditPricing {

    private CreditPricing() {
    }

    public static BigDecimal calculate(
            BigDecimal providerCostUsd,
            BigDecimal creditsPerUsd,
            BigDecimal minimumCharge
    ) {
        requireNonNegative(providerCostUsd, "providerCostUsd");
        requirePositive(creditsPerUsd, "creditsPerUsd");
        requireNonNegative(minimumCharge, "minimumCharge");

        BigDecimal calculated = providerCostUsd
                .multiply(creditsPerUsd)
                .setScale(0, RoundingMode.CEILING);
        return calculated.max(minimumCharge).setScale(0, RoundingMode.UNNECESSARY);
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
