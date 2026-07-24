package ru.itmo.nemat.orchestrator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.dead-letter")
@Getter
@Setter
public class KafkaDeadLetterProperties {

    private int batchSize = 50;
    private int maxRetryAttempts = 3;
    private Duration initialRetryDelay = Duration.ofSeconds(5);
    private Duration retryBaseDelay = Duration.ofSeconds(5);
    private Duration retryMaxDelay = Duration.ofMinutes(1);
    private Duration publishTimeout = Duration.ofSeconds(10);
}
