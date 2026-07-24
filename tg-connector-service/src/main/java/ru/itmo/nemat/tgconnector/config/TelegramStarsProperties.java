package ru.itmo.nemat.tgconnector.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "telegram.stars")
public class TelegramStarsProperties {

    private String productPayload = "pro-350-stars-v1";
    private int price = 350;
    private BigDecimal credits = new BigDecimal("300000");
}
