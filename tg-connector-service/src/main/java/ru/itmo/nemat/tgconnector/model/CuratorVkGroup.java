package ru.itmo.nemat.tgconnector.model;

import jakarta.persistence.*;
import lombok.*;
import ru.itmo.nemat.tgconnector.persistence.EncryptedStringConverter;
import java.util.UUID;

@Entity
@Table(name = "curator_vk_groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CuratorVkGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "curator_id", nullable = false)
    private Curator curator;

    @Column(name = "vk_group_id", nullable = false, unique = true)
    private String vkGroupId;

    @Column(name = "vk_token", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String vkToken;

    @Column(name = "vk_secret", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String vkSecret;

    @Column(name = "vk_confirmation_code", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String vkConfirmationCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private VkGroupStatus status;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "config_version", nullable = false)
    @Builder.Default
    private long configVersion = 1L;

    @Column(name = "pending_config_event_id")
    private UUID pendingConfigEventId;
}
