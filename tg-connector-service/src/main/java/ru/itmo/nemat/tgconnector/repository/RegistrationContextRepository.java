package ru.itmo.nemat.tgconnector.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.tgconnector.model.RegistrationContext;
import java.util.UUID;

@Repository
public interface RegistrationContextRepository extends JpaRepository<RegistrationContext, Long> {
}