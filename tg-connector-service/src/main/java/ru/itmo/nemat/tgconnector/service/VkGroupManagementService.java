package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VkGroupManagementService {

    private final CuratorVkGroupRepository groupRepository;
    private final CuratorRepository curatorRepository;
    private final VkGroupConfigProducer configProducer;

    @Transactional(readOnly = true)
    public List<CuratorVkGroup> findCuratorGroups(Long tgChatId) {
        return groupRepository.findAllByCuratorTgChatIdOrderByVkGroupId(tgChatId);
    }

    @Transactional
    public CuratorVkGroup registerGroup(
            Long tgChatId,
            RegistrationContext context,
            Subject subject
    ) {
        if (groupRepository.existsByVkGroupId(context.getVkGroupId())) {
            throw new IllegalStateException(
                    "VK group is already registered: " + context.getVkGroupId()
            );
        }

        Curator curator = curatorRepository.findByTgChatIdForUpdate(tgChatId)
                .orElseGet(() -> Curator.builder()
                        .tgChatId(tgChatId)
                        .username(context.getUsername())
                        .balanceTokens(new BigDecimal("10000"))
                        .reservedTokens(BigDecimal.ZERO)
                        .subject(subject)
                        .build());

        UUID eventId = UUID.randomUUID();
        long configVersion = groupRepository.nextConfigVersion();
        CuratorVkGroup group = CuratorVkGroup.builder()
                .curator(curator)
                .vkGroupId(context.getVkGroupId())
                .vkToken(context.getVkToken())
                .vkSecret(context.getVkSecret())
                .vkConfirmationCode(context.getVkConfirmationCode())
                .status(VkGroupStatus.PENDING)
                .configVersion(configVersion)
                .pendingConfigEventId(eventId)
                .build();

        curator.getVkGroups().add(group);
        curatorRepository.save(curator);

        configProducer.sendConfig(new VkGroupConfigEvent(
                eventId,
                group.getConfigVersion(),
                "UPSERT",
                group.getVkGroupId(),
                group.getVkToken(),
                group.getVkSecret(),
                group.getVkConfirmationCode(),
                subject.getSystemPrompt()
        ));
        return group;
    }

    @Transactional
    public boolean requestRemoval(Long tgChatId, String vkGroupId) {
        Optional<CuratorVkGroup> optionalGroup =
                groupRepository.findByVkGroupIdAndCuratorTgChatId(vkGroupId, tgChatId);
        if (optionalGroup.isEmpty()) {
            return false;
        }

        CuratorVkGroup group = optionalGroup.get();
        UUID eventId = UUID.randomUUID();
        group.setConfigVersion(groupRepository.nextConfigVersion());
        group.setPendingConfigEventId(eventId);
        group.setStatus(VkGroupStatus.REMOVING);
        group.setLastError(null);
        groupRepository.save(group);

        configProducer.sendConfig(new VkGroupConfigEvent(
                eventId,
                group.getConfigVersion(),
                "DELETE",
                group.getVkGroupId(),
                null,
                null,
                null,
                null
        ));
        return true;
    }

    @Transactional
    public Optional<GroupStatusUpdate> applyStatus(VkGroupConfigStatusEvent event) {
        return groupRepository.findByVkGroupId(event.vkGroupId())
                .flatMap(group -> {
                    if (group.getConfigVersion() != event.configVersion()
                            || !java.util.Objects.equals(
                                    group.getPendingConfigEventId(),
                                    event.eventId()
                            )) {
                        return Optional.empty();
                    }

                    Long tgChatId = group.getCurator().getTgChatId();
                    if ("REMOVED".equals(event.status())) {
                        groupRepository.delete(group);
                        return Optional.of(new GroupStatusUpdate(
                                tgChatId,
                                event.vkGroupId(),
                                "REMOVED",
                                null
                        ));
                    }

                    VkGroupStatus status = VkGroupStatus.valueOf(event.status());
                    group.setStatus(status);
                    group.setLastError(event.errorMessage());
                    group.setPendingConfigEventId(null);
                    groupRepository.save(group);
                    return Optional.of(new GroupStatusUpdate(
                            tgChatId,
                            event.vkGroupId(),
                            status.name(),
                            event.errorMessage()
                    ));
                });
    }

    public record GroupStatusUpdate(
            Long tgChatId,
            String vkGroupId,
            String status,
            String errorMessage
    ) {
    }
}
