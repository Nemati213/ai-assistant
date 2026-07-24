package ru.itmo.nemat.vkconnector.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.vkconnector.dto.VkGroupConfigEvent;
import ru.itmo.nemat.vkconnector.dto.VkGroupConfigStatusEvent;
import ru.itmo.nemat.vkconnector.model.VkGroupConfigStatusOutboxEvent;
import ru.itmo.nemat.vkconnector.model.VkGroupCredentials;
import ru.itmo.nemat.vkconnector.repository.VkGroupConfigStatusOutboxRepository;
import ru.itmo.nemat.vkconnector.repository.VkGroupCredentialsRepository;

import java.util.OptionalLong;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VkGroupCredentialsServiceTest {

    @Mock
    private VkGroupCredentialsRepository repository;
    @Mock
    private VkGroupConfigStatusOutboxRepository statusOutboxRepository;
    @Mock
    private VkApiService vkApiService;

    @InjectMocks
    private VkGroupCredentialsService service;

    @Test
    void returnsActiveAfterTokenValidationAndCallbackRegistration() {
        VkGroupConfigEvent event = upsertEvent();
        when(statusOutboxRepository.findById(event.eventId())).thenReturn(Optional.empty());
        when(repository.findByIdForUpdate("100")).thenReturn(Optional.empty());
        when(vkApiService.registerCallbackServer("100", "token", "secret")).thenReturn(77L);

        VkGroupConfigStatusEvent status = service.process(event);

        assertThat(status.status()).isEqualTo("ACTIVE");
        ArgumentCaptor<VkGroupCredentials> credentialsCaptor =
                ArgumentCaptor.forClass(VkGroupCredentials.class);
        verify(repository).save(credentialsCaptor.capture());
        assertThat(credentialsCaptor.getValue().getCallbackServerId()).isEqualTo(77L);
        assertThat(credentialsCaptor.getValue().getConfigVersion()).isEqualTo(1L);
        verify(vkApiService).validateGroupToken("100", "token");
        verify(statusOutboxRepository).save(
                org.mockito.ArgumentMatchers.any(VkGroupConfigStatusOutboxEvent.class)
        );
    }

    @Test
    void returnsErrorWhenVkRejectsToken() {
        VkGroupConfigEvent event = upsertEvent();
        when(statusOutboxRepository.findById(event.eventId())).thenReturn(Optional.empty());
        when(repository.findByIdForUpdate("100")).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new VkApiException("VK API error 5: authorization failed"))
                .when(vkApiService)
                .validateGroupToken("100", "token");

        VkGroupConfigStatusEvent status = service.process(event);

        assertThat(status.status()).isEqualTo("ERROR");
        assertThat(status.errorMessage()).contains("authorization failed");
    }

    @Test
    void removesRegisteredCallbackServerAndCredentials() {
        VkGroupCredentials credentials = new VkGroupCredentials();
        credentials.setVkGroupId("100");
        credentials.setVkToken("token");
        credentials.setConfigVersion(1L);
        credentials.setCallbackServerId(77L);
        UUID eventId = UUID.randomUUID();
        when(statusOutboxRepository.findById(eventId)).thenReturn(Optional.empty());
        when(repository.findByIdForUpdate("100")).thenReturn(Optional.of(credentials));
        when(vkApiService.findCallbackServerId("100", "token"))
                .thenReturn(OptionalLong.of(77L));

        VkGroupConfigStatusEvent status = service.process(
                new VkGroupConfigEvent(
                        eventId,
                        2L,
                        "DELETE",
                        "100",
                        null,
                        null,
                        null,
                        null
                )
        );

        assertThat(status.status()).isEqualTo("REMOVED");
        verify(vkApiService).deleteCallbackServer("100", "token", 77L);
        verify(repository).delete(credentials);
    }

    @Test
    void replaysStoredStatusWithoutCallingVkAgain() {
        UUID eventId = UUID.randomUUID();
        VkGroupConfigStatusOutboxEvent stored =
                VkGroupConfigStatusOutboxEvent.builder()
                        .eventId(eventId)
                        .configVersion(1L)
                        .vkGroupId("100")
                        .status("ACTIVE")
                        .createdAt(java.time.Instant.now())
                        .nextAttemptAt(java.time.Instant.now())
                        .build();
        when(statusOutboxRepository.findById(eventId))
                .thenReturn(Optional.of(stored));

        VkGroupConfigStatusEvent result = service.process(new VkGroupConfigEvent(
                eventId,
                1L,
                "UPSERT",
                "100",
                "token",
                "secret",
                "confirmation",
                "prompt"
        ));

        assertThat(result.status()).isEqualTo("ACTIVE");
        org.mockito.Mockito.verifyNoInteractions(vkApiService);
    }

    private VkGroupConfigEvent upsertEvent() {
        return new VkGroupConfigEvent(
                UUID.randomUUID(),
                1L,
                "UPSERT",
                "100",
                "token",
                "secret",
                "confirmation",
                "prompt"
        );
    }
}
