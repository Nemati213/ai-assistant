package ru.itmo.nemat.vkconnector.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.vk-delivery-retry")
@Getter
@Setter
public class VkDeliveryRetryProperties {

    private int batchSize = 50;
    private int maxAutomaticRetries = 3;
    private Duration retryBaseDelay = Duration.ofSeconds(2);
    private Duration retryMaxDelay = Duration.ofSeconds(30);
    private Duration processingTimeout = Duration.ofMinutes(2);
}
