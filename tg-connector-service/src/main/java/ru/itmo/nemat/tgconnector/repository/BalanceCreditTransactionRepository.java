package ru.itmo.nemat.tgconnector.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.tgconnector.model.BalanceCreditTransaction;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BalanceCreditTransactionRepository
        extends JpaRepository<BalanceCreditTransaction, UUID> {

    Optional<BalanceCreditTransaction> findByExternalId(String externalId);
}
