package ru.itmo.nemat.vkconnector.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.itmo.nemat.vkconnector.config.VkDeliveryRetryProperties;
import ru.itmo.nemat.vkconnector.repository.VkOutgoingDeliveryRepository;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class VkDeliveryRetryScheduler {

    private final VkOutgoingDeliveryRepository repository;
    private final VkOutgoingDeliveryService deliveryService;
    private final VkDeliveryRetryProperties properties;

    @Scheduled(fixedDelayString = "${app.vk-delivery-retry.poll-interval-ms:1000}")
    public void retryFailedDeliveries() {
        Instant now = Instant.now();
        for (UUID requestId : repository.findIdsReadyForAutomaticRetry(
                now,
                properties.getBatchSize()
        )) {
            try {
                deliveryService.retryDue(requestId, now);
            } catch (RuntimeException exception) {
                log.error(
                        "[{}] Failed to execute scheduled VK delivery retry",
                        requestId,
                        exception
                );
            }
        }

        Instant processingCutoff = now.minus(properties.getProcessingTimeout());
        for (UUID requestId : repository.findIdsWithStaleProcessing(
                processingCutoff,
                properties.getBatchSize()
        )) {
            try {
                deliveryService.recoverStaleProcessing(
                        requestId,
                        processingCutoff
                );
            } catch (RuntimeException exception) {
                log.error(
                        "[{}] Failed to recover stale VK delivery",
                        requestId,
                        exception
                );
            }
        }
    }
}
