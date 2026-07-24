package ru.itmo.nemat.tgconnector.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock
    private CuratorRepository curatorRepository;

    @InjectMocks
    private BalanceService balanceService;

    @Test
    void returnsCuratorBalance() {
        Curator curator = Curator.builder()
                .tgChatId(123L)
                .balanceTokens(new BigDecimal("875"))
                .reservedTokens(new BigDecimal("200"))
                .build();
        when(curatorRepository.findByTgChatId(123L)).thenReturn(Optional.of(curator));

        assertThat(balanceService.getBalance(123L))
                .contains(new BigDecimal("675"));
    }

    @Test
    void returnsEmptyForUnknownChat() {
        when(curatorRepository.findByTgChatId(123L)).thenReturn(Optional.empty());

        assertThat(balanceService.getBalance(123L)).isEmpty();
    }
}
