package ru.itmo.nemat.orchestrator.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.orchestrator.dto.VkGroupConfigEvent;
import ru.itmo.nemat.orchestrator.model.VkGroupPrompt;
import ru.itmo.nemat.orchestrator.repository.VkGroupPromptRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VkGroupPromptServiceTest {

    @Mock
    private VkGroupPromptRepository repository;
    @InjectMocks
    private VkGroupPromptService service;

    @Test
    void appliesNewerPromptVersion() {
        when(repository.findById("100")).thenReturn(Optional.empty());
        UUID eventId = UUID.randomUUID();

        service.apply(event(eventId, 7L, "new prompt"));

        ArgumentCaptor<VkGroupPrompt> captor =
                ArgumentCaptor.forClass(VkGroupPrompt.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getConfigVersion()).isEqualTo(7L);
        assertThat(captor.getValue().getLastConfigEventId()).isEqualTo(eventId);
        assertThat(captor.getValue().getSystemPrompt()).isEqualTo("new prompt");
    }

    @Test
    void ignoresOlderPromptVersion() {
        VkGroupPrompt prompt = prompt(8L);
        when(repository.findById("100")).thenReturn(Optional.of(prompt));

        service.apply(event(UUID.randomUUID(), 7L, "stale prompt"));

        assertThat(prompt.getSystemPrompt()).isEqualTo("current prompt");
        verify(repository, never()).save(prompt);
    }

    @Test
    void ignoresStaleRemovalAfterNewRegistration() {
        VkGroupPrompt prompt = prompt(8L);
        when(repository.findById("100")).thenReturn(Optional.of(prompt));

        service.removeAfterSuccessfulDisconnect("100", 7L);

        verify(repository, never()).delete(prompt);
    }

    private VkGroupConfigEvent event(
            UUID eventId,
            long version,
            String systemPrompt
    ) {
        return new VkGroupConfigEvent(
                eventId,
                version,
                "UPSERT",
                "100",
                "encrypted-token",
                "encrypted-secret",
                "encrypted-confirmation",
                systemPrompt
        );
    }

    private VkGroupPrompt prompt(long version) {
        VkGroupPrompt prompt = new VkGroupPrompt();
        prompt.setVkGroupId("100");
        prompt.setSystemPrompt("current prompt");
        prompt.setConfigVersion(version);
        prompt.setLastConfigEventId(UUID.randomUUID());
        return prompt;
    }
}
