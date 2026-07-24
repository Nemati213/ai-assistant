package ru.itmo.nemat.orchestrator.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.orchestrator.model.VkGroupPrompt;

@Repository
public interface VkGroupPromptRepository extends JpaRepository<VkGroupPrompt, String> {
}