package ru.itmo.nemat.tgconnector.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.tgconnector.model.Curator;

import java.util.List;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CuratorRepository extends JpaRepository<Curator, UUID> {
    Optional<Curator> findByTgChatId(Long tgChatId);

    List<Curator> findAllByOrderByTgChatIdAsc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select curator from Curator curator where curator.tgChatId = :tgChatId")
    Optional<Curator> findByTgChatIdForUpdate(@Param("tgChatId") Long tgChatId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select curator from Curator curator where curator.id = :id")
    Optional<Curator> findByIdForUpdate(@Param("id") UUID id);
}
