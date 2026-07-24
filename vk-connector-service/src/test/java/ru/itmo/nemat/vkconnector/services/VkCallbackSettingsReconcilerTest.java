package ru.itmo.nemat.vkconnector.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.vkconnector.model.VkGroupCredentials;
import ru.itmo.nemat.vkconnector.repository.VkGroupCredentialsRepository;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VkCallbackSettingsReconcilerTest {

    @Mock
    private VkGroupCredentialsRepository repository;
    @Mock
    private VkApiService vkApiService;

    @Test
    void enablesCurrentCallbackSettingsForExistingGroups() {
        VkGroupCredentials credentials = new VkGroupCredentials();
        credentials.setVkGroupId("100");
        credentials.setVkToken("token");
        credentials.setVkSecret("secret");
        credentials.setCallbackServerId(10L);
        when(repository.findAll()).thenReturn(List.of(credentials));
        when(vkApiService.registerCallbackServer("100", "token", "secret"))
                .thenReturn(10L);

        new VkCallbackSettingsReconciler(repository, vkApiService).reconcile();

        verify(vkApiService)
                .registerCallbackServer("100", "token", "secret");
    }
}
