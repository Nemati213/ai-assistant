package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BalanceService {

    private final CuratorRepository curatorRepository;

    @Transactional(readOnly = true)
    public Optional<BigDecimal> getBalance(Long tgChatId) {
        return curatorRepository.findByTgChatId(tgChatId)
                .map(curator -> {
                    BigDecimal balance = curator.getBalanceTokens() == null
                            ? BigDecimal.ZERO
                            : curator.getBalanceTokens();
                    BigDecimal reserved = curator.getReservedTokens() == null
                            ? BigDecimal.ZERO
                            : curator.getReservedTokens();
                    return balance.subtract(reserved);
                });
    }
}
