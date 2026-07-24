package ru.itmo.nemat.tgconnector.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.tgconnector.model.BillingTransaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;

@Repository
public interface BillingTransactionRepository extends JpaRepository<BillingTransaction, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select billing from BillingTransaction billing where billing.requestId = :requestId")
    java.util.Optional<BillingTransaction> findByIdForUpdate(
            @Param("requestId") UUID requestId
    );

    @Query(value = """
            SELECT *
            FROM billing_transactions
            WHERE result_published_at IS NULL
              AND next_publish_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<BillingTransaction> findReadyResults(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
