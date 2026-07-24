package ru.itmo.nemat.vkconnector.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.itmo.nemat.vkconnector.model.VkGroupCredentials;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface VkGroupCredentialsRepository extends JpaRepository<VkGroupCredentials, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select credentials
            from VkGroupCredentials credentials
            where credentials.vkGroupId = :vkGroupId
            """)
    Optional<VkGroupCredentials> findByIdForUpdate(
            @Param("vkGroupId") String vkGroupId
    );
}
