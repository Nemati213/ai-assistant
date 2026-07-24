package ru.itmo.nemat.tgconnector.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.tgconnector.model.Subject;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubjectRepository extends JpaRepository<Subject, UUID> {
    Optional<Subject> findByCode(String code);
}