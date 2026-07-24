package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.model.AdminAction;
import ru.itmo.nemat.tgconnector.model.BalanceCreditSource;
import ru.itmo.nemat.tgconnector.model.BalanceCreditTransaction;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.model.VkGroupStatus;
import ru.itmo.nemat.tgconnector.repository.AdminActionRepository;
import ru.itmo.nemat.tgconnector.repository.BalanceCreditTransactionRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminCuratorService {

    private static final BigDecimal MAX_MANUAL_CREDIT =
            new BigDecimal("1000000000");
    private static final int MAX_REASON_LENGTH = 255;
    private static final int CURATOR_LIST_PAGE_SIZE = 10;

    private final CuratorRepository curatorRepository;
    private final CuratorVkGroupRepository groupRepository;
    private final BalanceCreditTransactionRepository creditRepository;
    private final AdminActionRepository actionRepository;

    @Transactional(readOnly = true)
    public CuratorView findCurator(Long tgChatId) {
        Curator curator = curatorRepository.findByTgChatId(tgChatId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Куратор с Telegram ID " + tgChatId + " не найден."
                ));
        List<GroupView> groups =
                groupRepository.findAllByCuratorTgChatIdOrderByVkGroupId(tgChatId)
                        .stream()
                        .map(this::toGroupView)
                        .toList();

        BigDecimal balance = valueOrZero(curator.getBalanceTokens());
        BigDecimal reserved = valueOrZero(curator.getReservedTokens());
        return new CuratorView(
                curator.getId(),
                curator.getTgChatId(),
                curator.getUsername(),
                curator.getSubject().getName(),
                balance,
                reserved,
                balance.subtract(reserved),
                groups
        );
    }

    @Transactional(readOnly = true)
    public CuratorListView listCurators(int page) {
        if (page <= 0) {
            throw new IllegalArgumentException("Номер страницы должен быть больше нуля.");
        }

        long total = curatorRepository.count();
        int totalPages = Math.max(
                1,
                (int) Math.ceil((double) total / CURATOR_LIST_PAGE_SIZE)
        );
        int normalizedPage = Math.min(page, totalPages);

        List<CuratorSummary> curators = curatorRepository
                .findAllByOrderByTgChatIdAsc(
                        PageRequest.of(normalizedPage - 1, CURATOR_LIST_PAGE_SIZE)
                )
                .stream()
                .map(this::toCuratorSummary)
                .toList();

        return new CuratorListView(
                normalizedPage,
                totalPages,
                CURATOR_LIST_PAGE_SIZE,
                total,
                curators
        );
    }

    @Transactional
    public ManualCreditResult addTokens(
            long adminTgUserId,
            String adminUsername,
            Long targetTgChatId,
            BigDecimal amount,
            String reason,
            String operationKey
    ) {
        validateCreditCommand(targetTgChatId, operationKey);
        BigDecimal normalizedAmount = normalizeAmount(amount);
        String normalizedReason = normalizeReason(reason);

        Curator curator = curatorRepository.findByTgChatIdForUpdate(targetTgChatId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Куратор с Telegram ID " + targetTgChatId + " не найден."
                ));

        return creditRepository.findByExternalId(operationKey)
                .map(existing -> replay(existing, curator, normalizedAmount))
                .orElseGet(() -> applyCredit(
                        adminTgUserId,
                        adminUsername,
                        curator,
                        normalizedAmount,
                        normalizedReason,
                        operationKey
                ));
    }

    @Transactional(readOnly = true)
    public AdminStats stats() {
        long groups = groupRepository.count();
        long activeGroups = groupRepository.countByStatus(VkGroupStatus.ACTIVE);
        return new AdminStats(curatorRepository.count(), groups, activeGroups);
    }

    private ManualCreditResult applyCredit(
            long adminTgUserId,
            String adminUsername,
            Curator curator,
            BigDecimal amount,
            String reason,
            String operationKey
    ) {
        BigDecimal balanceAfter =
                valueOrZero(curator.getBalanceTokens()).add(amount);
        curator.setBalanceTokens(balanceAfter);

        creditRepository.save(BalanceCreditTransaction.builder()
                .id(UUID.randomUUID())
                .curatorId(curator.getId())
                .source(BalanceCreditSource.ADMIN_MANUAL)
                .externalId(operationKey)
                .credits(amount)
                .starsAmount(0)
                .currency("ADMIN")
                .invoicePayload(reason)
                .balanceAfter(balanceAfter)
                .createdAt(Instant.now())
                .build());

        actionRepository.save(AdminAction.builder()
                .id(UUID.randomUUID())
                .operationKey(operationKey)
                .adminTgUserId(adminTgUserId)
                .adminUsername(normalizeUsername(adminUsername))
                .actionType("ADD_TOKENS")
                .targetCuratorId(curator.getId())
                .targetTgChatId(curator.getTgChatId())
                .amount(amount)
                .reason(reason)
                .details("Balance after: " + balanceAfter.toPlainString())
                .createdAt(Instant.now())
                .build());

        return new ManualCreditResult(amount, balanceAfter, true);
    }

    private ManualCreditResult replay(
            BalanceCreditTransaction existing,
            Curator curator,
            BigDecimal amount
    ) {
        if (existing.getSource() != BalanceCreditSource.ADMIN_MANUAL
                || !existing.getCuratorId().equals(curator.getId())
                || existing.getCredits().compareTo(amount) != 0) {
            throw new IllegalStateException(
                    "Ключ админской операции уже использован с другими данными."
            );
        }
        return new ManualCreditResult(
                existing.getCredits(),
                existing.getBalanceAfter(),
                false
        );
    }

    private void validateCreditCommand(
            Long targetTgChatId,
            String operationKey
    ) {
        if (targetTgChatId == null || targetTgChatId <= 0) {
            throw new IllegalArgumentException("Telegram ID должен быть положительным.");
        }
        if (operationKey == null || operationKey.isBlank()) {
            throw new IllegalArgumentException("Ключ операции обязателен.");
        }
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Количество токенов должно быть больше нуля.");
        }
        BigDecimal normalized = amount.stripTrailingZeros();
        if (normalized.scale() > 2) {
            throw new IllegalArgumentException(
                    "Допускается не более двух знаков после запятой."
            );
        }
        if (normalized.compareTo(MAX_MANUAL_CREDIT) > 0) {
            throw new IllegalArgumentException(
                    "Одной операцией нельзя начислить больше "
                            + MAX_MANUAL_CREDIT.toPlainString() + " токенов."
            );
        }
        return normalized;
    }

    private String normalizeReason(String reason) {
        String value = reason == null || reason.isBlank()
                ? "Ручное начисление администратором"
                : reason.strip();
        return value.length() <= MAX_REASON_LENGTH
                ? value
                : value.substring(0, MAX_REASON_LENGTH);
    }

    private String normalizeUsername(String username) {
        return username == null || username.isBlank()
                ? null
                : username.strip();
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private GroupView toGroupView(CuratorVkGroup group) {
        return new GroupView(
                group.getVkGroupId(),
                group.getStatus(),
                group.getLastError()
        );
    }

    private CuratorSummary toCuratorSummary(Curator curator) {
        BigDecimal balance = valueOrZero(curator.getBalanceTokens());
        BigDecimal reserved = valueOrZero(curator.getReservedTokens());
        List<CuratorVkGroup> groups = curator.getVkGroups() == null
                ? List.of()
                : curator.getVkGroups();
        long activeGroups = groups.stream()
                .filter(group -> group.getStatus() == VkGroupStatus.ACTIVE)
                .count();

        return new CuratorSummary(
                curator.getId(),
                curator.getTgChatId(),
                curator.getUsername(),
                curator.getSubject().getName(),
                balance,
                reserved,
                balance.subtract(reserved),
                groups.size(),
                activeGroups
        );
    }

    public record CuratorView(
            UUID id,
            Long tgChatId,
            String username,
            String subject,
            BigDecimal balance,
            BigDecimal reserved,
            BigDecimal available,
            List<GroupView> groups
    ) {
    }

    public record GroupView(
            String vkGroupId,
            VkGroupStatus status,
            String lastError
    ) {
    }

    public record CuratorSummary(
            UUID id,
            Long tgChatId,
            String username,
            String subject,
            BigDecimal balance,
            BigDecimal reserved,
            BigDecimal available,
            int groups,
            long activeGroups
    ) {
    }

    public record CuratorListView(
            int page,
            int totalPages,
            int pageSize,
            long total,
            List<CuratorSummary> curators
    ) {
    }

    public record ManualCreditResult(
            BigDecimal credited,
            BigDecimal balanceAfter,
            boolean newlyCredited
    ) {
    }

    public record AdminStats(
            long curators,
            long groups,
            long activeGroups
    ) {
    }
}
