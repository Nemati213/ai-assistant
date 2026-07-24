package ru.itmo.nemat.tgconnector.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.nemat.tgconnector.model.BalanceReservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BalanceReservationRepository
        extends JpaRepository<BalanceReservation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation
            from BalanceReservation reservation
            where reservation.requestId = :requestId
            """)
    Optional<BalanceReservation> findByIdForUpdate(@Param("requestId") UUID requestId);

    @Query(value = """
            SELECT *
            FROM balance_reservations
            WHERE status = 'RESERVED'
              AND expires_at <= :now
            ORDER BY expires_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<BalanceReservation> findExpiredForUpdate(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
