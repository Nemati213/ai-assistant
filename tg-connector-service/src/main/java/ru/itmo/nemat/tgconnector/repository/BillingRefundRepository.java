package ru.itmo.nemat.tgconnector.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.tgconnector.model.BillingRefund;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface BillingRefundRepository extends JpaRepository<BillingRefund, UUID> {

    @Query(value = """
            SELECT *
            FROM billing_refunds
            WHERE result_published_at IS NULL
              AND next_publish_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<BillingRefund> findReadyResults(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
