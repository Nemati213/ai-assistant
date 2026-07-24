package ru.itmo.nemat.orchestrator.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.orchestrator.model.WorkflowState;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowStateRepository extends JpaRepository<WorkflowState, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from WorkflowState state where state.requestId = :requestId")
    Optional<WorkflowState> findByIdForUpdate(@Param("requestId") UUID requestId);

    @Query(value = """
            SELECT request_id
            FROM workflow_states
            WHERE status = :status
              AND status_changed_at <= :cutoff
              AND recovery_attempts < :maxAttempts
            ORDER BY status_changed_at
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findIdsForWatchdog(
            @Param("status") String status,
            @Param("cutoff") Instant cutoff,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize
    );

    @Query(value = """
            SELECT request_id
            FROM workflow_states
            WHERE status = :status
              AND recovery_attempts >= :maxAttempts
              AND recovery_exhausted_notified_at IS NULL
            ORDER BY status_changed_at
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findIdsWithExhaustedRecovery(
            @Param("status") String status,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize
    );
}
