package ru.itmo.nemat.vkconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.vkconnector.dto.VkGroupConfigEvent;
import ru.itmo.nemat.vkconnector.services.VkGroupCredentialsService;
import ru.itmo.nemat.shared.security.SecretCipher;

@Component
@Slf4j
@RequiredArgsConstructor
public class VkGroupConfigConsumer {

    private final ObjectMapper objectMapper;
    private final VkGroupCredentialsService credentialsService;
    private final SecretCipher secretCipher;

    @KafkaListener(topics = "vk-group-configs", groupId = "vk-config-group")
    public void consume(String payload) {
        try {
            VkGroupConfigEvent event = decryptSecrets(
                    objectMapper.readValue(payload, VkGroupConfigEvent.class)
            );
            var status = credentialsService.process(event);
            log.info("Group {} configuration finished with status {}", event.vkGroupId(), status.status());
        } catch (Exception e) {
            log.error("Failed to process group config", e);
            throw new IllegalStateException("Failed to process group config", e);
        }
    }

    private VkGroupConfigEvent decryptSecrets(VkGroupConfigEvent event) {
        if (!"UPSERT".equals(event.action())) {
            return event;
        }
        requireEncrypted(event.vkToken(), "vkToken");
        requireEncrypted(event.vkSecret(), "vkSecret");
        requireEncrypted(event.vkConfirmationCode(), "vkConfirmationCode");
        return new VkGroupConfigEvent(
                event.eventId(),
                event.configVersion(),
                event.action(),
                event.vkGroupId(),
                secretCipher.decrypt(event.vkToken()),
                secretCipher.decrypt(event.vkSecret()),
                secretCipher.decrypt(event.vkConfirmationCode()),
                event.systemPrompt()
        );
    }

    private void requireEncrypted(String value, String fieldName) {
        if (!secretCipher.isEncrypted(value)) {
            throw new IllegalArgumentException(
                    fieldName + " must be encrypted in VK group config events"
            );
        }
    }
}
