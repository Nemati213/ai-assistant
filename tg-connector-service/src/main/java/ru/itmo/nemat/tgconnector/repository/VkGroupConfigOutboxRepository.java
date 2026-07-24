package ru.itmo.nemat.tgconnector.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.nemat.tgconnector.model.VkGroupConfigOutboxEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VkGroupConfigOutboxRepository
        extends JpaRepository<VkGroupConfigOutboxEvent, UUID> {

    @Query(value = """
            SELECT *
            FROM vk_group_config_outbox
            WHERE published_at IS NULL
              AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<VkGroupConfigOutboxEvent> findReadyForPublishing(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query("""
            delete from VkGroupConfigOutboxEvent event
            where event.publishedAt is not null
              and event.publishedAt < :cutoff
            """)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
