package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.model.VkGroupStatus;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CuratorRoutingService {

    private final CuratorVkGroupRepository groupRepository;

    @Transactional(readOnly = true)
    public Optional<CuratorRoute> resolve(String vkGroupId) {
        return groupRepository.findByVkGroupId(vkGroupId)
                .filter(group -> group.getStatus() == VkGroupStatus.ACTIVE)
                .map(CuratorVkGroup::getCurator)
                .map(curator -> new CuratorRoute(curator.getTgChatId()));
    }

    @Transactional(readOnly = true)
    public Optional<CuratorRoute> resolveRegistered(String vkGroupId) {
        return groupRepository.findByVkGroupId(vkGroupId)
                .map(CuratorVkGroup::getCurator)
                .map(curator -> new CuratorRoute(curator.getTgChatId()));
    }

    public record CuratorRoute(Long tgChatId) {
    }
}
