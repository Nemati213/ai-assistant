package ru.itmo.nemat.orchestrator.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.orchestrator.model.VkGroupPrompt;
import ru.itmo.nemat.orchestrator.repository.VkGroupPromptRepository;
import ru.itmo.nemat.orchestrator.dto.VkGroupConfigEvent;

@Service
@RequiredArgsConstructor
public class VkGroupPromptService {

    private final VkGroupPromptRepository repository;

    @Transactional
    public void apply(VkGroupConfigEvent event) {
        if ("DELETE".equals(event.action())) {
            return;
        }
        if (!"UPSERT".equals(event.action())) {
            throw new IllegalArgumentException("Unsupported VK group config action " + event.action());
        }

        VkGroupPrompt prompt = repository.findById(event.vkGroupId())
                .orElseGet(() -> {
                    VkGroupPrompt newEntity = new VkGroupPrompt();
                    newEntity.setVkGroupId(event.vkGroupId());
                    return newEntity;
                });

        if (event.configVersion() < prompt.getConfigVersion()) {
            return;
        }
        if (event.configVersion() == prompt.getConfigVersion()
                && !event.eventId().equals(prompt.getLastConfigEventId())) {
            return;
        }

        prompt.setSystemPrompt(event.systemPrompt());
        prompt.setConfigVersion(event.configVersion());
        prompt.setLastConfigEventId(event.eventId());
        repository.save(prompt);
    }

    @Transactional
    public void removeAfterSuccessfulDisconnect(String vkGroupId, long configVersion) {
        repository.findById(vkGroupId)
                .filter(prompt -> configVersion > prompt.getConfigVersion())
                .ifPresent(repository::delete);
    }
}
