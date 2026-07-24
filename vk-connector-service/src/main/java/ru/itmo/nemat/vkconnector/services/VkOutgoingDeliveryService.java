package ru.itmo.nemat.vkconnector.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.vkconnector.config.VkDeliveryRetryProperties;
import ru.itmo.nemat.vkconnector.dto.SendVkMessageCommand;
import ru.itmo.nemat.vkconnector.model.VkOutgoingDelivery;
import ru.itmo.nemat.vkconnector.model.VkOutgoingDeliveryStatus;
import ru.itmo.nemat.vkconnector.repository.VkOutgoingDeliveryRepository;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class VkOutgoingDeliveryService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final VkOutgoingDeliveryRepository repository;
    private final VkApiService vkApiService;
    private final VkDeliveryFailureClassifier failureClassifier;
    private final VkDeliveryRetryProperties retryProperties;

    @Transactional
    public void deliver(SendVkMessageCommand command) {
        int deliveryAttempt = effectiveDeliveryAttempt(command.deliveryAttempt());
        VkOutgoingDelivery delivery = repository
                .findByIdForUpdate(command.requestId())
                .map(existing -> validateReplay(existing, command))
                .orElseGet(() -> createDelivery(command, deliveryAttempt));

        if (deliveryAttempt < delivery.getDeliveryAttempt()) {
            log.info(
                    "[{}] Stale VK outgoing attempt {} ignored; current attempt is {}",
                    command.requestId(),
                    deliveryAttempt,
                    delivery.getDeliveryAttempt()
            );
            return;
        }
        if (deliveryAttempt > delivery.getDeliveryAttempt()) {
            if (delivery.getStatus() == VkOutgoingDeliveryStatus.SUCCEEDED) {
                log.info(
                        "[{}] VK delivery already succeeded; retry attempt {} ignored",
                        command.requestId(),
                        deliveryAttempt
                );
                return;
            }
            delivery.beginRetry(deliveryAttempt, Instant.now());
        }
        if (delivery.getStatus() == VkOutgoingDeliveryStatus.RETRY_PENDING) {
            log.info(
                    "[{}] Duplicate VK command ignored while automatic retry is pending",
                    command.requestId()
            );
            return;
        }
        if (delivery.isTerminal()) {
            log.info(
                    "[{}] Duplicate VK outgoing command ignored in status {}",
                    command.requestId(),
                    delivery.getStatus()
            );
            return;
        }

        attemptDelivery(delivery, command);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryDue(UUID requestId, Instant now) {
        VkOutgoingDelivery delivery = repository.findByIdForUpdate(requestId)
                .orElse(null);
        if (delivery == null
                || delivery.getStatus() != VkOutgoingDeliveryStatus.RETRY_PENDING
                || delivery.getNextDeliveryAttemptAt() == null
                || delivery.getNextDeliveryAttemptAt().isAfter(now)) {
            return false;
        }

        delivery.beginAutomaticRetry(Instant.now());
        attemptDelivery(delivery, toCommand(delivery));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverStaleProcessing(UUID requestId, Instant cutoff) {
        VkOutgoingDelivery delivery = repository.findByIdForUpdate(requestId)
                .orElse(null);
        if (delivery == null
                || delivery.getStatus() != VkOutgoingDeliveryStatus.PROCESSING
                || delivery.getUpdatedAt().isAfter(cutoff)) {
            return false;
        }

        delivery.beginAutomaticRetry(Instant.now());
        attemptDelivery(delivery, toCommand(delivery));
        return true;
    }

    private void attemptDelivery(
            VkOutgoingDelivery delivery,
            SendVkMessageCommand command
    ) {
        try {
            long vkMessageId = vkApiService.sendMessage(command);
            delivery.markSucceeded(vkMessageId, Instant.now());
        } catch (Exception exception) {
            String error = normalizeError(exception);
            VkDeliveryFailureClassifier.Classification classification =
                    failureClassifier.classify(exception);
            if (classification.retryable()
                    && delivery.getAutomaticRetryAttempts()
                    < retryProperties.getMaxAutomaticRetries()) {
                Duration delay = retryDelay(delivery.getAutomaticRetryAttempts());
                delivery.scheduleAutomaticRetry(
                        error,
                        classification.category(),
                        Instant.now().plus(delay),
                        Instant.now()
                );
                log.warn(
                        "[{}] Temporary VK delivery error {}, automatic retry {} scheduled in {} ms",
                        command.requestId(),
                        classification.category(),
                        delivery.getAutomaticRetryAttempts(),
                        delay.toMillis()
                );
                return;
            }

            delivery.markFailed(
                    error,
                    classification.category(),
                    Instant.now()
            );
            log.warn(
                    "[{}] Permanent or exhausted VK delivery error {} stored: {}",
                    command.requestId(),
                    classification.category(),
                    error
            );
        }
    }

    private VkOutgoingDelivery createDelivery(
            SendVkMessageCommand command,
            int deliveryAttempt
    ) {
        Instant now = Instant.now();
        return repository.saveAndFlush(VkOutgoingDelivery.builder()
                .requestId(command.requestId())
                .vkChatId(command.vkChatId())
                .vkGroupId(command.vkGroupId())
                .messageText(command.text())
                .deliveryAttempt(deliveryAttempt)
                .status(VkOutgoingDeliveryStatus.PROCESSING)
                .createdAt(now)
                .updatedAt(now)
                .nextPublishAt(now)
                .build());
    }

    private VkOutgoingDelivery validateReplay(
            VkOutgoingDelivery existing,
            SendVkMessageCommand command
    ) {
        if (!existing.getVkChatId().equals(command.vkChatId())
                || !existing.getVkGroupId().equals(command.vkGroupId())
                || !existing.getMessageText().equals(command.text())) {
            throw new IllegalStateException(
                    "VK outgoing requestId was reused with different parameters"
            );
        }
        return existing;
    }

    private String normalizeError(Exception exception) {
        Throwable cause = exception.getCause() == null
                ? exception
                : exception.getCause();
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }

    private int effectiveDeliveryAttempt(int deliveryAttempt) {
        return deliveryAttempt <= 0 ? 1 : deliveryAttempt;
    }

    private Duration retryDelay(int completedRetries) {
        int exponent = Math.min(completedRetries, 16);
        long multiplier = 1L << exponent;
        long delayMillis;
        try {
            delayMillis = Math.multiplyExact(
                    retryProperties.getRetryBaseDelay().toMillis(),
                    multiplier
            );
        } catch (ArithmeticException exception) {
            delayMillis = Long.MAX_VALUE;
        }
        return Duration.ofMillis(Math.min(
                retryProperties.getRetryMaxDelay().toMillis(),
                delayMillis
        ));
    }

    private SendVkMessageCommand toCommand(VkOutgoingDelivery delivery) {
        return new SendVkMessageCommand(
                delivery.getRequestId(),
                delivery.getVkChatId(),
                delivery.getVkGroupId(),
                delivery.getMessageText(),
                delivery.getDeliveryAttempt()
        );
    }
}
