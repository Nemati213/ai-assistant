package ru.itmo.nemat.tgconnector.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.tgconnector.model.AdminAction;
import ru.itmo.nemat.tgconnector.model.BalanceCreditSource;
import ru.itmo.nemat.tgconnector.model.BalanceCreditTransaction;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.model.Subject;
import ru.itmo.nemat.tgconnector.model.VkGroupStatus;
import ru.itmo.nemat.tgconnector.repository.AdminActionRepository;
import ru.itmo.nemat.tgconnector.repository.BalanceCreditTransactionRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCuratorServiceTest {

    @Mock
    private CuratorRepository curatorRepository;
    @Mock
    private CuratorVkGroupRepository groupRepository;
    @Mock
    private BalanceCreditTransactionRepository creditRepository;
    @Mock
    private AdminActionRepository actionRepository;

    private AdminCuratorService service;

    @BeforeEach
    void setUp() {
        service = new AdminCuratorService(
                curatorRepository,
                groupRepository,
                creditRepository,
                actionRepository
        );
    }

    @Test
    void listsCuratorsWithBalancesAndGroupCounters() {
        Curator first = curator("10000");
        first.setUsername("alice");
        first.setReservedTokens(new BigDecimal("1000"));
        first.getVkGroups().add(group(first, "math-group", VkGroupStatus.ACTIVE));
        first.getVkGroups().add(group(first, "old-group", VkGroupStatus.ERROR));

        Curator second = curator("2500");
        second.setTgChatId(456L);
        second.setUsername(null);
        second.setSubject(subject("Физика"));

        when(curatorRepository.count()).thenReturn(2L);
        when(curatorRepository.findAllByOrderByTgChatIdAsc(any(Pageable.class)))
                .thenReturn(List.of(first, second));

        AdminCuratorService.CuratorListView result = service.listCurators(1);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.curators()).hasSize(2);

        AdminCuratorService.CuratorSummary firstSummary =
                result.curators().getFirst();
        assertThat(firstSummary.tgChatId()).isEqualTo(123L);
        assertThat(firstSummary.username()).isEqualTo("alice");
        assertThat(firstSummary.subject()).isEqualTo("Математика");
        assertThat(firstSummary.balance()).isEqualByComparingTo("10000");
        assertThat(firstSummary.reserved()).isEqualByComparingTo("1000");
        assertThat(firstSummary.available()).isEqualByComparingTo("9000");
        assertThat(firstSummary.groups()).isEqualTo(2);
        assertThat(firstSummary.activeGroups()).isEqualTo(1);

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);
        verify(curatorRepository)
                .findAllByOrderByTgChatIdAsc(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void creditsCuratorAndWritesAuditRecordAtomically() {
        Curator curator = curator("10000");
        when(curatorRepository.findByTgChatIdForUpdate(123L))
                .thenReturn(Optional.of(curator));
        when(creditRepository.findByExternalId("admin:1:update:10"))
                .thenReturn(Optional.empty());

        AdminCuratorService.ManualCreditResult result = service.addTokens(
                1L,
                "owner",
                123L,
                new BigDecimal("5000.00"),
                "Перевод на карту",
                "admin:1:update:10"
        );

        assertThat(result.newlyCredited()).isTrue();
        assertThat(result.credited()).isEqualByComparingTo("5000");
        assertThat(result.balanceAfter()).isEqualByComparingTo("15000");
        assertThat(curator.getBalanceTokens()).isEqualByComparingTo("15000");

        ArgumentCaptor<BalanceCreditTransaction> creditCaptor =
                ArgumentCaptor.forClass(BalanceCreditTransaction.class);
        verify(creditRepository).save(creditCaptor.capture());
        assertThat(creditCaptor.getValue().getSource())
                .isEqualTo(BalanceCreditSource.ADMIN_MANUAL);
        assertThat(creditCaptor.getValue().getStarsAmount()).isZero();
        assertThat(creditCaptor.getValue().getExternalId())
                .isEqualTo("admin:1:update:10");

        ArgumentCaptor<AdminAction> actionCaptor =
                ArgumentCaptor.forClass(AdminAction.class);
        verify(actionRepository).save(actionCaptor.capture());
        assertThat(actionCaptor.getValue().getAdminTgUserId()).isEqualTo(1L);
        assertThat(actionCaptor.getValue().getTargetTgChatId()).isEqualTo(123L);
        assertThat(actionCaptor.getValue().getAmount())
                .isEqualByComparingTo("5000");
    }

    @Test
    void replayedTelegramUpdateDoesNotCreditTwice() {
        Curator curator = curator("15000");
        BalanceCreditTransaction existing = BalanceCreditTransaction.builder()
                .id(UUID.randomUUID())
                .curatorId(curator.getId())
                .source(BalanceCreditSource.ADMIN_MANUAL)
                .externalId("admin:1:update:10")
                .credits(new BigDecimal("5000"))
                .starsAmount(0)
                .currency("ADMIN")
                .invoicePayload("Перевод на карту")
                .balanceAfter(new BigDecimal("15000"))
                .createdAt(Instant.now())
                .build();
        when(curatorRepository.findByTgChatIdForUpdate(123L))
                .thenReturn(Optional.of(curator));
        when(creditRepository.findByExternalId("admin:1:update:10"))
                .thenReturn(Optional.of(existing));

        AdminCuratorService.ManualCreditResult result = service.addTokens(
                1L,
                "owner",
                123L,
                new BigDecimal("5000"),
                "Перевод на карту",
                "admin:1:update:10"
        );

        assertThat(result.newlyCredited()).isFalse();
        assertThat(curator.getBalanceTokens()).isEqualByComparingTo("15000");
        verify(creditRepository, never()).save(any());
        verify(actionRepository, never()).save(any());
    }

    @Test
    void rejectsSuspiciousOrInvalidAmountsBeforeBalanceMutation() {
        assertThatThrownBy(() -> service.addTokens(
                1L,
                "owner",
                123L,
                new BigDecimal("-1"),
                null,
                "admin:1:update:11"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.addTokens(
                1L,
                "owner",
                123L,
                new BigDecimal("1000000001"),
                null,
                "admin:1:update:12"
        )).isInstanceOf(IllegalArgumentException.class);

        verify(curatorRepository, never()).findByTgChatIdForUpdate(any());
    }

    private Curator curator(String balance) {
        return Curator.builder()
                .id(UUID.randomUUID())
                .tgChatId(123L)
                .subject(subject("Математика"))
                .balanceTokens(new BigDecimal(balance))
                .reservedTokens(BigDecimal.ZERO)
                .build();
    }

    private Subject subject(String name) {
        return Subject.builder()
                .id(UUID.randomUUID())
                .code(name.toLowerCase())
                .name(name)
                .systemPrompt("Prompt")
                .build();
    }

    private CuratorVkGroup group(
            Curator curator,
            String vkGroupId,
            VkGroupStatus status
    ) {
        return CuratorVkGroup.builder()
                .id(UUID.randomUUID())
                .curator(curator)
                .vkGroupId(vkGroupId)
                .status(status)
                .build();
    }
}
