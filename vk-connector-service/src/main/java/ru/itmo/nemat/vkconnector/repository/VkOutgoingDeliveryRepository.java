package ru.itmo.nemat.vkconnector.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.nemat.vkconnector.model.VkOutgoingDelivery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VkOutgoingDeliveryRepository
        extends JpaRepository<VkOutgoingDelivery, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select delivery
            from VkOutgoingDelivery delivery
            where delivery.requestId = :requestId
            """)
    Optional<VkOutgoingDelivery> findByIdForUpdate(
            @Param("requestId") UUID requestId
    );

    @Query(value = """
            SELECT request_id
            FROM vk_outgoing_deliveries
            WHERE status = 'RETRY_PENDING'
              AND next_delivery_attempt_at <= :now
            ORDER BY next_delivery_attempt_at
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findIdsReadyForAutomaticRetry(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    @Query(value = """
            SELECT request_id
            FROM vk_outgoing_deliveries
            WHERE status = 'PROCESSING'
              AND updated_at <= :cutoff
            ORDER BY updated_at
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findIdsWithStaleProcessing(
            @Param("cutoff") Instant cutoff,
            @Param("batchSize") int batchSize
    );

    @Query(value = """
            SELECT *
            FROM vk_outgoing_deliveries
            WHERE status IN ('SUCCEEDED', 'FAILED')
              AND result_published_at IS NULL
              AND next_publish_at <= :now
            ORDER BY updated_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<VkOutgoingDelivery> findReadyForPublishing(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query("""
            delete from VkOutgoingDelivery delivery
            where delivery.resultPublishedAt is not null
              and delivery.resultPublishedAt < :cutoff
            """)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
