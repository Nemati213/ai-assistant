package ru.itmo.nemat.vkconnector.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vk_outgoing_deliveries")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VkOutgoingDelivery {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "vk_chat_id", nullable = false)
    private String vkChatId;

    @Column(name = "vk_group_id", nullable = false)
    private String vkGroupId;

    @Column(name = "message_text", nullable = false, columnDefinition = "TEXT")
    private String messageText;

    @Column(name = "delivery_attempt", nullable = false)
    private int deliveryAttempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VkOutgoingDeliveryStatus status;

    @Column(name = "vk_message_id")
    private Long vkMessageId;

    @Column(name = "delivery_error", columnDefinition = "TEXT")
    private String deliveryError;

    @Column(name = "delivery_error_category")
    private String deliveryErrorCategory;

    @Builder.Default
    @Column(name = "automatic_retry_attempts", nullable = false)
    private int automaticRetryAttempts = 0;

    @Column(name = "next_delivery_attempt_at")
    private Instant nextDeliveryAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "result_published_at")
    private Instant resultPublishedAt;

    @Builder.Default
    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts = 0;

    @Column(name = "next_publish_at", nullable = false)
    private Instant nextPublishAt;

    @Column(name = "publish_error", columnDefinition = "TEXT")
    private String publishError;

    public boolean isTerminal() {
        return status == VkOutgoingDeliveryStatus.SUCCEEDED
                || status == VkOutgoingDeliveryStatus.FAILED;
    }

    public void markSucceeded(long messageId, Instant now) {
        this.status = VkOutgoingDeliveryStatus.SUCCEEDED;
        this.vkMessageId = messageId;
        this.deliveryError = null;
        this.deliveryErrorCategory = null;
        this.nextDeliveryAttemptAt = null;
        this.updatedAt = now;
        this.nextPublishAt = now;
    }

    public void markFailed(String error, String category, Instant now) {
        this.status = VkOutgoingDeliveryStatus.FAILED;
        this.vkMessageId = null;
        this.deliveryError = error;
        this.deliveryErrorCategory = category;
        this.nextDeliveryAttemptAt = null;
        this.updatedAt = now;
        this.nextPublishAt = now;
    }

    public void scheduleAutomaticRetry(
            String error,
            String category,
            Instant nextAttemptAt,
            Instant now
    ) {
        this.status = VkOutgoingDeliveryStatus.RETRY_PENDING;
        this.vkMessageId = null;
        this.deliveryError = error;
        this.deliveryErrorCategory = category;
        this.automaticRetryAttempts++;
        this.nextDeliveryAttemptAt = nextAttemptAt;
        this.updatedAt = now;
    }

    public void beginAutomaticRetry(Instant now) {
        this.status = VkOutgoingDeliveryStatus.PROCESSING;
        this.updatedAt = now;
        this.nextDeliveryAttemptAt = null;
    }

    public void beginRetry(int deliveryAttempt, Instant now) {
        this.deliveryAttempt = deliveryAttempt;
        this.status = VkOutgoingDeliveryStatus.PROCESSING;
        this.vkMessageId = null;
        this.deliveryError = null;
        this.deliveryErrorCategory = null;
        this.automaticRetryAttempts = 0;
        this.nextDeliveryAttemptAt = null;
        this.updatedAt = now;
        this.resultPublishedAt = null;
        this.publishAttempts = 0;
        this.nextPublishAt = now;
        this.publishError = null;
    }

    public void markResultPublished(Instant now) {
        this.resultPublishedAt = now;
        this.publishError = null;
    }

    public void recordPublishFailure(String error, Instant nextAttemptAt) {
        this.publishAttempts++;
        this.publishError = error;
        this.nextPublishAt = nextAttemptAt;
    }
}
