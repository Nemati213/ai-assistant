package ru.itmo.nemat.vkconnector.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.itmo.nemat.vkconnector.model.VkGroupCredentials;
import ru.itmo.nemat.vkconnector.repository.VkGroupCredentialsRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class VkCallbackSettingsReconciler {

    private final VkGroupCredentialsRepository repository;
    private final VkApiService vkApiService;

    @Scheduled(
            initialDelayString = "${vk.callback.reconcile-initial-delay-ms:30000}",
            fixedDelayString = "${vk.callback.reconcile-interval-ms:21600000}"
    )
    public void reconcile() {
        for (VkGroupCredentials credentials : repository.findAll()) {
            try {
                long serverId = vkApiService.registerCallbackServer(
                        credentials.getVkGroupId(),
                        credentials.getVkToken(),
                        credentials.getVkSecret()
                );
                if (credentials.getCallbackServerId() == null
                        || credentials.getCallbackServerId() != serverId) {
                    credentials.setCallbackServerId(serverId);
                    repository.save(credentials);
                }
            } catch (RuntimeException exception) {
                log.error(
                        "Failed to reconcile VK callback settings for group {}",
                        credentials.getVkGroupId(),
                        exception
                );
            }
        }
    }
}
