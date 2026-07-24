package ru.itmo.nemat.vkconnector.model;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import ru.itmo.nemat.vkconnector.persistence.EncryptedStringConverter;

import java.util.UUID;

@Entity
@Table(name = "vk_group_credentials")
public class VkGroupCredentials {

    @Id
    @Column(name = "vk_group_id")
    private String vkGroupId;

    @Column(name = "vk_token", columnDefinition = "TEXT", nullable = false)
    @Convert(converter = EncryptedStringConverter.class)
    private String vkToken;

    @Column(name = "vk_secret", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String vkSecret;

    @Column(name = "vk_confirmation_code", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String vkConfirmationCode;

    @Column(name = "callback_server_id")
    private Long callbackServerId;

    @Column(name = "config_version", nullable = false)
    private long configVersion;

    @Column(name = "last_config_event_id")
    private UUID lastConfigEventId;

    public VkGroupCredentials() {
    }

    public String getVkGroupId() {
        return vkGroupId;
    }

    public void setVkGroupId(String vkGroupId) {
        this.vkGroupId = vkGroupId;
    }

    public String getVkToken() {
        return vkToken;
    }

    public void setVkToken(String vkToken) {
        this.vkToken = vkToken;
    }

    public String getVkSecret() {
        return vkSecret;
    }

    public void setVkSecret(String vkSecret) {
        this.vkSecret = vkSecret;
    }

    public String getVkConfirmationCode() {
        return vkConfirmationCode;
    }

    public void setVkConfirmationCode(String vkConfirmationCode) {
        this.vkConfirmationCode = vkConfirmationCode;
    }

    public Long getCallbackServerId() {
        return callbackServerId;
    }

    public void setCallbackServerId(Long callbackServerId) {
        this.callbackServerId = callbackServerId;
    }

    public long getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(long configVersion) {
        this.configVersion = configVersion;
    }

    public UUID getLastConfigEventId() {
        return lastConfigEventId;
    }

    public void setLastConfigEventId(UUID lastConfigEventId) {
        this.lastConfigEventId = lastConfigEventId;
    }
}
