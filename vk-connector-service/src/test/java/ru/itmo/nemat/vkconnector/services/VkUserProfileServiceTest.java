package ru.itmo.nemat.vkconnector.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.vkconnector.model.VkUserProfile;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VkUserProfileServiceTest {

    @Mock
    private VkApiService vkApiService;

    @Test
    void cachesResolvedProfileByGroupAndUser() {
        VkUserProfile profile =
                new VkUserProfile("Иван", "Петров", "Иван Петров");
        when(vkApiService.getUserProfile("100", "300"))
                .thenReturn(Optional.of(profile));
        VkUserProfileService service = new VkUserProfileService(
                vkApiService,
                Duration.ofHours(24),
                100
        );

        assertThat(service.resolve("100", "300")).contains(profile);
        assertThat(service.resolve("100", "300")).contains(profile);

        verify(vkApiService, times(1)).getUserProfile("100", "300");
    }

    @Test
    void providerFailureDoesNotBreakMessageProcessing() {
        when(vkApiService.getUserProfile("100", "300"))
                .thenThrow(new VkApiException("VK unavailable"));
        VkUserProfileService service = new VkUserProfileService(
                vkApiService,
                Duration.ofMinutes(1),
                100
        );

        assertThat(service.resolve("100", "300")).isEmpty();
        assertThat(service.resolve("100", "300")).isEmpty();
        verify(vkApiService, times(2)).getUserProfile("100", "300");
    }
}
