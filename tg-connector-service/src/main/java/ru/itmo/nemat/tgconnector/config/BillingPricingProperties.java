package ru.itmo.nemat.tgconnector.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.billing.pricing")
public class BillingPricingProperties {

    private BigDecimal creditsPerUsd = new BigDecimal("200000");
    private BigDecimal minimumCharge = new BigDecimal("100");
    private BigDecimal reservationCredits = new BigDecimal("1000");
}
