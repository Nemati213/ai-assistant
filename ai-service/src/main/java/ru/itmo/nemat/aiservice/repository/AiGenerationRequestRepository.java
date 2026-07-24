package ru.itmo.nemat.aiservice.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.aiservice.model.AiGenerationRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AiGenerationRequestRepository
        extends JpaRepository<AiGenerationRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from AiGenerationRequest request
            where request.requestId = :requestId
            """)
    Optional<AiGenerationRequest> findByIdForUpdate(
            @Param("requestId") UUID requestId
    );

    @Query(value = """
            SELECT *
            FROM ai_generation_requests
            WHERE status IN ('COMPLETED', 'FAILED')
              AND result_published_at IS NULL
              AND next_publish_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AiGenerationRequest> findReadyResults(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    @Query(value = """
            SELECT *
            FROM ai_generation_requests
            WHERE status = 'PROCESSING'
              AND started_at < :cutoff
            ORDER BY started_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AiGenerationRequest> findStaleProcessing(
            @Param("cutoff") Instant cutoff,
            @Param("batchSize") int batchSize
    );
}
