package ru.itmo.nemat.tgconnector.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.nemat.tgconnector.model.CuratorIntakeRequestState;
import ru.itmo.nemat.tgconnector.model.CuratorIntakeStatus;

import java.util.Optional;
import java.util.UUID;

public interface CuratorIntakeRequestRepository
        extends JpaRepository<CuratorIntakeRequestState, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from CuratorIntakeRequestState request
            where request.requestId = :requestId
            """)
    Optional<CuratorIntakeRequestState> findByIdForUpdate(
            @Param("requestId") UUID requestId
    );

    Optional<CuratorIntakeRequestState>
            findByTgChatIdAndManualPromptMessageIdAndStatus(
                    Long tgChatId,
                    Integer manualPromptMessageId,
                    CuratorIntakeStatus status
            );
}
