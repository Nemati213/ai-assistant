package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.config.TelegramStarsProperties;
import ru.itmo.nemat.tgconnector.model.BalanceCreditSource;
import ru.itmo.nemat.tgconnector.model.BalanceCreditTransaction;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.repository.BalanceCreditTransactionRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BalanceCreditService {

    private static final String STARS_CURRENCY = "XTR";

    private final BalanceCreditTransactionRepository transactionRepository;
    private final CuratorRepository curatorRepository;
    private final TelegramStarsProperties properties;

    @Transactional
    public CreditResult creditTelegramStars(
            Long tgChatId,
            String currency,
            int starsAmount,
            String invoicePayload,
            String telegramPaymentChargeId
    ) {
        validate(currency, starsAmount, invoicePayload, telegramPaymentChargeId);

        Curator curator = curatorRepository.findByTgChatIdForUpdate(tgChatId)
                .orElseThrow(() -> new IllegalArgumentException("Curator is not registered"));

        return transactionRepository.findByExternalId(telegramPaymentChargeId)
                .map(transaction -> replayResult(curator, transaction, starsAmount, invoicePayload))
                .orElseGet(() -> applyCredit(
                        curator,
                        starsAmount,
                        invoicePayload,
                        telegramPaymentChargeId
                ));
    }

    private CreditResult applyCredit(
            Curator curator,
            int starsAmount,
            String invoicePayload,
            String telegramPaymentChargeId
    ) {
        BigDecimal currentBalance = curator.getBalanceTokens() == null
                ? BigDecimal.ZERO
                : curator.getBalanceTokens();
        BigDecimal balanceAfter = currentBalance.add(properties.getCredits());
        curator.setBalanceTokens(balanceAfter);

        transactionRepository.save(BalanceCreditTransaction.builder()
                .id(UUID.randomUUID())
                .curatorId(curator.getId())
                .source(BalanceCreditSource.TELEGRAM_STARS)
                .externalId(telegramPaymentChargeId)
                .credits(properties.getCredits())
                .starsAmount(starsAmount)
                .currency(STARS_CURRENCY)
                .invoicePayload(invoicePayload)
                .balanceAfter(balanceAfter)
                .createdAt(Instant.now())
                .build());

        return new CreditResult(properties.getCredits(), balanceAfter, true);
    }

    private CreditResult replayResult(
            Curator curator,
            BalanceCreditTransaction transaction,
            int starsAmount,
            String invoicePayload
    ) {
        if (!transaction.getCuratorId().equals(curator.getId())
                || transaction.getStarsAmount() != starsAmount
                || !transaction.getInvoicePayload().equals(invoicePayload)) {
            throw new IllegalStateException(
                    "Telegram payment charge id was reused with different payment data"
            );
        }
        return new CreditResult(
                transaction.getCredits(),
                transaction.getBalanceAfter(),
                false
        );
    }

    private void validate(
            String currency,
            int starsAmount,
            String invoicePayload,
            String telegramPaymentChargeId
    ) {
        if (!STARS_CURRENCY.equals(currency)) {
            throw new IllegalArgumentException("Unsupported payment currency");
        }
        if (starsAmount != properties.getPrice()) {
            throw new IllegalArgumentException("Unexpected Telegram Stars amount");
        }
        if (!properties.getProductPayload().equals(invoicePayload)) {
            throw new IllegalArgumentException("Unknown invoice payload");
        }
        if (telegramPaymentChargeId == null || telegramPaymentChargeId.isBlank()) {
            throw new IllegalArgumentException("Telegram payment charge id is required");
        }
    }

    public record CreditResult(
            BigDecimal credited,
            BigDecimal balanceAfter,
            boolean newlyCredited
    ) {
    }
}
