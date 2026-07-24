package ru.itmo.nemat.tgconnector.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.model.VkGroupStatus;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public interface CuratorVkGroupRepository extends JpaRepository<CuratorVkGroup, UUID> {
    boolean existsByVkGroupId(String vkGroupId);

    long countByStatus(VkGroupStatus status);

    @EntityGraph(attributePaths = "curator")
    Optional<CuratorVkGroup> findByVkGroupId(String vkGroupId);

    @EntityGraph(attributePaths = "curator")
    Optional<CuratorVkGroup> findByVkGroupIdAndCuratorTgChatId(String vkGroupId, Long tgChatId);

    List<CuratorVkGroup> findAllByCuratorTgChatIdOrderByVkGroupId(Long tgChatId);

    @Query(value = "SELECT nextval('vk_group_config_version_seq')", nativeQuery = true)
    long nextConfigVersion();
}
