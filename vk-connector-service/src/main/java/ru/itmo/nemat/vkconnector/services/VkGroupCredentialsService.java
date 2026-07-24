package ru.itmo.nemat.vkconnector.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.vkconnector.dto.VkGroupConfigEvent;
import ru.itmo.nemat.vkconnector.dto.VkGroupConfigStatusEvent;
import ru.itmo.nemat.vkconnector.model.VkGroupConfigStatusOutboxEvent;
import ru.itmo.nemat.vkconnector.model.VkGroupCredentials;
import ru.itmo.nemat.vkconnector.repository.VkGroupConfigStatusOutboxRepository;
import ru.itmo.nemat.vkconnector.repository.VkGroupCredentialsRepository;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class VkGroupCredentialsService {

    private final VkGroupCredentialsRepository repository;
    private final VkGroupConfigStatusOutboxRepository statusOutboxRepository;
    private final VkApiService vkApiService;

    @Transactional
    public VkGroupConfigStatusEvent process(VkGroupConfigEvent event) {
        validate(event);

        VkGroupConfigStatusOutboxEvent existingStatus =
                statusOutboxRepository.findById(event.eventId()).orElse(null);
        if (existingStatus != null) {
            return toStatusEvent(existingStatus);
        }

        VkGroupConfigStatusEvent result;
        try {
            result = switch (event.action()) {
                case "UPSERT" -> connectGroup(event);
                case "DELETE" -> removeGroup(event);
                default -> throw new IllegalArgumentException(
                        "Unsupported VK group config action " + event.action()
                );
            };
        } catch (Exception exception) {
            result = new VkGroupConfigStatusEvent(
                    event.eventId(),
                    event.configVersion(),
                    event.vkGroupId(),
                    "ERROR",
                    safeErrorMessage(exception)
            );
        }

        statusOutboxRepository.save(toOutboxEvent(result));
        return result;
    }

    private VkGroupConfigStatusEvent connectGroup(VkGroupConfigEvent event) {
        VkGroupCredentials credentials = repository.findByIdForUpdate(event.vkGroupId())
                .orElse(null);
        if (credentials != null) {
            if (credentials.getConfigVersion() == event.configVersion()
                    && event.eventId().equals(credentials.getLastConfigEventId())) {
                return activeStatus(event);
            }
            requireNewerVersion(event, credentials);
        }

        vkApiService.validateGroupToken(event.vkGroupId(), event.vkToken());
        long callbackServerId = vkApiService.registerCallbackServer(
                event.vkGroupId(),
                event.vkToken(),
                event.vkSecret()
        );

        if (credentials == null) {
            credentials = new VkGroupCredentials();
            credentials.setVkGroupId(event.vkGroupId());
        }
        credentials.setVkToken(event.vkToken());
        credentials.setVkSecret(event.vkSecret());
        credentials.setVkConfirmationCode(event.vkConfirmationCode());
        credentials.setCallbackServerId(callbackServerId);
        credentials.setConfigVersion(event.configVersion());
        credentials.setLastConfigEventId(event.eventId());
        repository.save(credentials);

        return activeStatus(event);
    }

    private VkGroupConfigStatusEvent removeGroup(VkGroupConfigEvent event) {
        VkGroupCredentials credentials =
                repository.findByIdForUpdate(event.vkGroupId()).orElse(null);
        if (credentials != null) {
            requireNewerVersion(event, credentials);
            Long callbackServerId = vkApiService.findCallbackServerId(
                    event.vkGroupId(),
                    credentials.getVkToken()
            ).stream().boxed().findFirst().orElse(null);
            if (callbackServerId != null) {
                vkApiService.deleteCallbackServer(
                        event.vkGroupId(),
                        credentials.getVkToken(),
                        callbackServerId
                );
            }
            repository.delete(credentials);
        }

        return new VkGroupConfigStatusEvent(
                event.eventId(),
                event.configVersion(),
                event.vkGroupId(),
                "REMOVED",
                null
        );
    }

    private VkGroupConfigStatusEvent activeStatus(VkGroupConfigEvent event) {
        return new VkGroupConfigStatusEvent(
                event.eventId(),
                event.configVersion(),
                event.vkGroupId(),
                "ACTIVE",
                null
        );
    }

    private void requireNewerVersion(
            VkGroupConfigEvent event,
            VkGroupCredentials credentials
    ) {
        if (event.configVersion() <= credentials.getConfigVersion()) {
            throw new IllegalStateException(
                    "Stale VK group config version " + event.configVersion()
                            + ", current version is " + credentials.getConfigVersion()
            );
        }
    }

    private VkGroupConfigStatusOutboxEvent toOutboxEvent(
            VkGroupConfigStatusEvent event
    ) {
        Instant now = Instant.now();
        return VkGroupConfigStatusOutboxEvent.builder()
                .eventId(event.eventId())
                .configVersion(event.configVersion())
                .vkGroupId(event.vkGroupId())
                .status(event.status())
                .errorMessage(event.errorMessage())
                .createdAt(now)
                .attempts(0)
                .nextAttemptAt(now)
                .build();
    }

    private VkGroupConfigStatusEvent toStatusEvent(
            VkGroupConfigStatusOutboxEvent event
    ) {
        return new VkGroupConfigStatusEvent(
                event.getEventId(),
                event.getConfigVersion(),
                event.getVkGroupId(),
                event.getStatus(),
                event.getErrorMessage()
        );
    }

    private void validate(VkGroupConfigEvent event) {
        if (event.eventId() == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (event.configVersion() <= 0) {
            throw new IllegalArgumentException("configVersion must be positive");
        }
        if (event.vkGroupId() == null || event.vkGroupId().isBlank()) {
            throw new IllegalArgumentException("vkGroupId is required");
        }
    }

    private String safeErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Unknown error while configuring VK group";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
