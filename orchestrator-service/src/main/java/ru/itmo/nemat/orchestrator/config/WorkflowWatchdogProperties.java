package ru.itmo.nemat.orchestrator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.workflow-watchdog")
@Getter
@Setter
public class WorkflowWatchdogProperties {

    private int batchSize = 50;
    private int maxRecoveryAttempts = 10;
    private Duration curatorActionTimeout = Duration.ofMinutes(10);
    private Duration reservationTimeout = Duration.ofMinutes(2);
    private Duration aiTimeout = Duration.ofMinutes(3);
    private Duration approvalDeliveryTimeout = Duration.ofMinutes(10);
    private Duration billingTimeout = Duration.ofMinutes(2);
    private Duration vkDeliveryTimeout = Duration.ofMinutes(2);
    private Duration refundTimeout = Duration.ofMinutes(2);
}
