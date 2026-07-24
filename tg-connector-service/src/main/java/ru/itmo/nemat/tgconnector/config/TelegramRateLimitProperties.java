package ru.itmo.nemat.tgconnector.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "telegram.rate-limit")
public class TelegramRateLimitProperties {

    private String redisUri = "redis://localhost:6379";
    private String bucketKey = "telegram:global-api-limit";
    private long capacity = 30;
    private long refillTokens = 30;
    private Duration refillPeriod = Duration.ofSeconds(1);
    private int maxTelegramRetries = 3;
}
