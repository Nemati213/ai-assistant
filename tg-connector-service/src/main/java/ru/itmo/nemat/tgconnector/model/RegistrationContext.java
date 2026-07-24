package ru.itmo.nemat.tgconnector.model;

import jakarta.persistence.*;
import lombok.*;
import ru.itmo.nemat.tgconnector.persistence.EncryptedStringConverter;
import java.util.UUID;

@Entity
@Table(name = "registration_contexts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistrationContext {

    @Id
    @Column(name = "tg_chat_id")
    private Long tgChatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private RegistrationState state;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "username")
    private String username;

    @Column(name = "vk_group_id")
    private String vkGroupId;

    @Column(name = "vk_token", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String vkToken;

    @Column(name = "vk_secret", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String vkSecret;

    @Column(name = "vk_confirmation_code", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String vkConfirmationCode;
}
