package ru.itmo.nemat.tgconnector.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.nemat.tgconnector.model.CuratorDecisionRequest;
import ru.itmo.nemat.tgconnector.model.CuratorDecisionRequestStatus;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CuratorDecisionRequestRepository
        extends JpaRepository<CuratorDecisionRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from CuratorDecisionRequest request
            where request.requestId = :requestId
            """)
    Optional<CuratorDecisionRequest> findByIdForUpdate(
            @Param("requestId") UUID requestId
    );

    Optional<CuratorDecisionRequest> findByTgChatIdAndEditPromptMessageIdAndStatus(
            Long tgChatId,
            Integer editPromptMessageId,
            CuratorDecisionRequestStatus status
    );

    List<CuratorDecisionRequest>
    findAllByTgChatIdAndStatusInOrderByCreatedAtAsc(
            Long tgChatId,
            Collection<CuratorDecisionRequestStatus> statuses
    );
}
