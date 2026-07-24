package ru.itmo.nemat.orchestrator.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.orchestrator.model.OutboxEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    boolean existsByDeduplicationKey(String deduplicationKey);

    @Query(value = """
            SELECT *
            FROM outbox_events
            WHERE published_at IS NULL
              AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findReadyForPublishing(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query("delete from OutboxEvent event where event.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
