package ru.itmo.nemat.orchestrator.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.nemat.orchestrator.model.KafkaDeadLetter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface KafkaDeadLetterRepository extends JpaRepository<KafkaDeadLetter, UUID> {

    boolean existsByDltTopicAndDltPartitionAndDltOffset(
            String dltTopic,
            int dltPartition,
            long dltOffset
    );

    @Query(value = """
            SELECT *
            FROM kafka_dead_letters
            WHERE status IN ('PENDING', 'PUBLISH_FAILED')
              AND next_retry_at <= :now
            ORDER BY received_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<KafkaDeadLetter> findReadyForRetry(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
