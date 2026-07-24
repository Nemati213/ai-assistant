package ru.itmo.nemat.tgconnector.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.dto.VkGroupConfigEvent;
import ru.itmo.nemat.shared.security.SecretCipher;
import ru.itmo.nemat.tgconnector.model.VkGroupConfigOutboxEvent;
import ru.itmo.nemat.tgconnector.repository.VkGroupConfigOutboxRepository;

import java.time.Instant;

@Component
@Slf4j
@RequiredArgsConstructor
public class VkGroupConfigProducer {

    private final VkGroupConfigOutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void sendConfig(VkGroupConfigEvent event) {
        try {
            Instant now = Instant.now();
            repository.save(VkGroupConfigOutboxEvent.builder()
                    .eventId(event.eventId())
                    .vkGroupId(event.vkGroupId())
                    .configVersion(event.configVersion())
                    .payload(objectMapper.writeValueAsString(encryptSecrets(event)))
                    .createdAt(now)
                    .attempts(0)
                    .nextAttemptAt(now)
                    .build());
            log.debug(
                    "[{}] VK group {} config v{} stored in outbox",
                    event.eventId(),
                    event.vkGroupId(),
                    event.configVersion()
            );
        } catch (Exception e) {
            log.error("Failed to store VK group configuration in outbox", e);
            throw new IllegalStateException(
                    "Failed to store VK group configuration in outbox",
                    e
            );
        }
    }

    private VkGroupConfigEvent encryptSecrets(VkGroupConfigEvent event) {
        if (!"UPSERT".equals(event.action())) {
            return event;
        }
        return new VkGroupConfigEvent(
                event.eventId(),
                event.configVersion(),
                event.action(),
                event.vkGroupId(),
                secretCipher.encrypt(event.vkToken()),
                secretCipher.encrypt(event.vkSecret()),
                secretCipher.encrypt(event.vkConfirmationCode()),
                event.systemPrompt()
        );
    }
}
