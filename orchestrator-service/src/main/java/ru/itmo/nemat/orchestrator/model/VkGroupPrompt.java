package ru.itmo.nemat.orchestrator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "vk_group_prompts")
public class VkGroupPrompt {

    @Id
    @Column(name = "vk_group_id")
    private String vkGroupId;

    @Column(name = "system_prompt", columnDefinition = "TEXT", nullable = false)
    private String systemPrompt;

    @Column(name = "config_version", nullable = false)
    private long configVersion;

    @Column(name = "last_config_event_id")
    private UUID lastConfigEventId;

    public VkGroupPrompt() {
    }

    public String getVkGroupId() {
        return vkGroupId;
    }

    public void setVkGroupId(String vkGroupId) {
        this.vkGroupId = vkGroupId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
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
