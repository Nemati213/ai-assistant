package ru.itmo.nemat.vkconnector.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.nemat.vkconnector.model.VkWebhookOutboxEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VkWebhookOutboxRepository
        extends JpaRepository<VkWebhookOutboxEvent, UUID> {

    boolean existsByDeduplicationKey(String deduplicationKey);

    @Query(value = """
            SELECT *
            FROM vk_webhook_outbox
            WHERE published_at IS NULL
              AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<VkWebhookOutboxEvent> findReadyForPublishing(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query("""
            delete from VkWebhookOutboxEvent event
            where event.publishedAt is not null
              and event.publishedAt < :cutoff
            """)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
