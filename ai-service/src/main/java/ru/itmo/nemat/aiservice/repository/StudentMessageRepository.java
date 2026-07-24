package ru.itmo.nemat.aiservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.nemat.aiservice.model.StudentMessage;

import java.util.List;
import java.util.UUID;

public interface StudentMessageRepository
        extends JpaRepository<StudentMessage, UUID> {

    @Query(value = """
            SELECT *
            FROM student_messages
            WHERE student_id = :studentId
              AND request_id <> :excludedRequestId
            ORDER BY created_at DESC, id DESC
            LIMIT :messageLimit
            """, nativeQuery = true)
    List<StudentMessage> findRecentMessagesExcludingRequest(
            @Param("studentId") UUID studentId,
            @Param("excludedRequestId") UUID excludedRequestId,
            @Param("messageLimit") int messageLimit
    );
}
