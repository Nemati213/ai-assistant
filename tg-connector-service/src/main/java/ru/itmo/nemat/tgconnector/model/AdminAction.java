package ru.itmo.nemat.tgconnector.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_actions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAction {

    @Id
    private UUID id;

    @Column(name = "operation_key", nullable = false, unique = true)
    private String operationKey;

    @Column(name = "admin_tg_user_id", nullable = false)
    private Long adminTgUserId;

    @Column(name = "admin_username")
    private String adminUsername;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "target_curator_id")
    private UUID targetCuratorId;

    @Column(name = "target_tg_chat_id")
    private Long targetTgChatId;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
