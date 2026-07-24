package ru.itmo.nemat.orchestrator.services;

import org.junit.jupiter.api.Test;
import ru.itmo.nemat.shared.billing.CreditPricing;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CreditPricingTest {

    @Test
    void calculatesCreditsFromProviderCost() {
        assertThat(CreditPricing.calculate(
                new BigDecimal("0.005"),
                new BigDecimal("200000"),
                new BigDecimal("100")
        )).isEqualByComparingTo("1000");
    }

    @Test
    void roundsFractionalCreditsUp() {
        assertThat(CreditPricing.calculate(
                new BigDecimal("0.000501"),
                new BigDecimal("200000"),
                new BigDecimal("100")
        )).isEqualByComparingTo("101");
    }

    @Test
    void appliesMinimumCharge() {
        assertThat(CreditPricing.calculate(
                BigDecimal.ZERO,
                new BigDecimal("200000"),
                new BigDecimal("100")
        )).isEqualByComparingTo("100");
    }
}
