package ru.itmo.nemat.tgconnector.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.tgconnector.dto.VkGroupConfigEvent;
import ru.itmo.nemat.tgconnector.dto.VkGroupConfigStatusEvent;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.model.RegistrationContext;
import ru.itmo.nemat.tgconnector.model.Subject;
import ru.itmo.nemat.tgconnector.model.VkGroupStatus;
import ru.itmo.nemat.tgconnector.producer.VkGroupConfigProducer;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VkGroupManagementServiceTest {

    @Mock
    private CuratorVkGroupRepository groupRepository;
    @Mock
    private CuratorRepository curatorRepository;
    @Mock
    private VkGroupConfigProducer configProducer;

    @InjectMocks
    private VkGroupManagementService service;

    @Test
    void marksOwnedGroupAsRemovingAndPublishesDelete() {
        CuratorVkGroup group = group();
        when(groupRepository.findByVkGroupIdAndCuratorTgChatId("100", 10L))
                .thenReturn(Optional.of(group));
        when(groupRepository.nextConfigVersion()).thenReturn(2L);

        boolean requested = service.requestRemoval(10L, "100");

        assertThat(requested).isTrue();
        assertThat(group.getStatus()).isEqualTo(VkGroupStatus.REMOVING);
        ArgumentCaptor<VkGroupConfigEvent> eventCaptor =
                ArgumentCaptor.forClass(VkGroupConfigEvent.class);
        verify(configProducer).sendConfig(eventCaptor.capture());
        assertThat(eventCaptor.getValue().action()).isEqualTo("DELETE");
        assertThat(eventCaptor.getValue().configVersion()).isEqualTo(2L);
        assertThat(group.getPendingConfigEventId())
                .isEqualTo(eventCaptor.getValue().eventId());
    }

    @Test
    void refusesToRemoveAnotherCuratorsGroup() {
        when(groupRepository.findByVkGroupIdAndCuratorTgChatId("100", 10L))
                .thenReturn(Optional.empty());

        assertThat(service.requestRemoval(10L, "100")).isFalse();
    }

    @Test
    void appliesActiveStatusAndClearsError() {
        CuratorVkGroup group = group();
        group.setLastError("old error");
        UUID eventId = group.getPendingConfigEventId();
        when(groupRepository.findByVkGroupId("100")).thenReturn(Optional.of(group));

        var update = service.applyStatus(
                new VkGroupConfigStatusEvent(eventId, 1L, "100", "ACTIVE", null)
        );

        assertThat(update).isPresent();
        assertThat(group.getStatus()).isEqualTo(VkGroupStatus.ACTIVE);
        assertThat(group.getLastError()).isNull();
        assertThat(group.getPendingConfigEventId()).isNull();
    }

    @Test
    void deletesGroupAfterRemovedStatus() {
        CuratorVkGroup group = group();
        UUID eventId = group.getPendingConfigEventId();
        when(groupRepository.findByVkGroupId("100")).thenReturn(Optional.of(group));

        var update = service.applyStatus(
                new VkGroupConfigStatusEvent(eventId, 1L, "100", "REMOVED", null)
        );

        assertThat(update).isPresent();
        verify(groupRepository).delete(group);
    }

    @Test
    void ignoresStaleStatusFromOlderConfigEvent() {
        CuratorVkGroup group = group();
        when(groupRepository.findByVkGroupId("100")).thenReturn(Optional.of(group));

        var update = service.applyStatus(new VkGroupConfigStatusEvent(
                UUID.randomUUID(),
                1L,
                "100",
                "ERROR",
                "late error"
        ));

        assertThat(update).isEmpty();
        assertThat(group.getStatus()).isEqualTo(VkGroupStatus.ACTIVE);
    }

    @Test
    void storesNewGroupAndConfigEventTogether() {
        Curator curator = Curator.builder()
                .tgChatId(10L)
                .balanceTokens(java.math.BigDecimal.TEN)
                .vkGroups(new ArrayList<>())
                .build();
        Subject subject = Subject.builder()
                .systemPrompt("prompt")
                .build();
        RegistrationContext context = RegistrationContext.builder()
                .username("curator")
                .vkGroupId("100")
                .vkToken("token")
                .vkSecret("secret")
                .vkConfirmationCode("confirmation")
                .build();
        when(groupRepository.existsByVkGroupId("100")).thenReturn(false);
        when(curatorRepository.findByTgChatIdForUpdate(10L))
                .thenReturn(Optional.of(curator));
        when(groupRepository.nextConfigVersion()).thenReturn(7L);

        CuratorVkGroup group = service.registerGroup(10L, context, subject);

        assertThat(group.getConfigVersion()).isEqualTo(7L);
        assertThat(group.getStatus()).isEqualTo(VkGroupStatus.PENDING);
        verify(curatorRepository).save(curator);
        ArgumentCaptor<VkGroupConfigEvent> eventCaptor =
                ArgumentCaptor.forClass(VkGroupConfigEvent.class);
        verify(configProducer).sendConfig(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventId())
                .isEqualTo(group.getPendingConfigEventId());
    }

    private CuratorVkGroup group() {
        Curator curator = Curator.builder()
                .tgChatId(10L)
                .build();
        return CuratorVkGroup.builder()
                .vkGroupId("100")
                .curator(curator)
                .status(VkGroupStatus.ACTIVE)
                .configVersion(1L)
                .pendingConfigEventId(UUID.randomUUID())
                .build();
    }
}
