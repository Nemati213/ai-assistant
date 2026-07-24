package ru.itmo.nemat.tgconnector.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.model.VkGroupStatus;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CuratorRoutingServiceTest {

    @Mock
    private CuratorVkGroupRepository groupRepository;

    @InjectMocks
    private CuratorRoutingService routingService;

    @Test
    void resolvesCuratorChatForVkGroup() {
        Curator curator = Curator.builder()
                .tgChatId(12345L)
                .balanceTokens(new BigDecimal("42"))
                .build();
        CuratorVkGroup group = CuratorVkGroup.builder()
                .vkGroupId("777")
                .curator(curator)
                .status(VkGroupStatus.ACTIVE)
                .build();
        when(groupRepository.findByVkGroupId("777")).thenReturn(Optional.of(group));

        Optional<CuratorRoutingService.CuratorRoute> route = routingService.resolve("777");

        assertThat(route).hasValueSatisfying(value -> {
            assertThat(value.tgChatId()).isEqualTo(12345L);
        });
    }

    @Test
    void resolvesRouteWhenBalanceIsFullyReserved() {
        Curator curator = Curator.builder()
                .tgChatId(12345L)
                .balanceTokens(new BigDecimal("1000"))
                .reservedTokens(new BigDecimal("1000"))
                .build();
        CuratorVkGroup group = CuratorVkGroup.builder()
                .vkGroupId("777")
                .curator(curator)
                .status(VkGroupStatus.ACTIVE)
                .build();
        when(groupRepository.findByVkGroupId("777")).thenReturn(Optional.of(group));

        Optional<CuratorRoutingService.CuratorRoute> route = routingService.resolve("777");

        assertThat(route).hasValueSatisfying(
                value -> assertThat(value.tgChatId()).isEqualTo(12345L)
        );
    }

    @Test
    void returnsEmptyRouteForUnknownGroup() {
        when(groupRepository.findByVkGroupId("missing")).thenReturn(Optional.empty());

        assertThat(routingService.resolve("missing")).isEmpty();
    }

    @Test
    void resolvesRegisteredRouteEvenWhenGroupIsNotActive() {
        Curator curator = Curator.builder()
                .tgChatId(12345L)
                .balanceTokens(BigDecimal.ZERO)
                .build();
        CuratorVkGroup group = CuratorVkGroup.builder()
                .vkGroupId("777")
                .curator(curator)
                .status(VkGroupStatus.ERROR)
                .build();
        when(groupRepository.findByVkGroupId("777")).thenReturn(Optional.of(group));

        assertThat(routingService.resolveRegistered("777"))
                .hasValueSatisfying(route ->
                        assertThat(route.tgChatId()).isEqualTo(12345L)
                );
    }
}
