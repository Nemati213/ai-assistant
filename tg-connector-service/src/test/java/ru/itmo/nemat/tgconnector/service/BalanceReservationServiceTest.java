package ru.itmo.nemat.tgconnector.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.tgconnector.dto.BalanceReleaseCommand;
import ru.itmo.nemat.tgconnector.dto.BalanceReservationCommand;
import ru.itmo.nemat.tgconnector.dto.BalanceReservationResultEvent;
import ru.itmo.nemat.tgconnector.model.BalanceReservation;
import ru.itmo.nemat.tgconnector.model.BalanceReservationStatus;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.model.VkGroupStatus;
import ru.itmo.nemat.tgconnector.repository.BalanceReservationRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceReservationServiceTest {

    @Mock
    private BalanceReservationRepository reservationRepository;
    @Mock
    private CuratorVkGroupRepository groupRepository;
    @Mock
    private CuratorRepository curatorRepository;
    @InjectMocks
    private BalanceReservationService service;

    @Test
    void reservesAvailableBalanceAtomically() {
        UUID requestId = UUID.randomUUID();
        Curator curator = curator(new BigDecimal("1500"), BigDecimal.ZERO);
        arrangeNewReservation(requestId, curator);

        BalanceReservationResultEvent result = service.reserve(command(requestId));

        assertThat(result.status()).isEqualTo("RESERVED");
        assertThat(result.availableBalance()).isEqualByComparingTo("500");
        assertThat(curator.getReservedTokens()).isEqualByComparingTo("1000");

        ArgumentCaptor<BalanceReservation> captor =
                ArgumentCaptor.forClass(BalanceReservation.class);
        verify(reservationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(BalanceReservationStatus.RESERVED);
    }

    @Test
    void rejectsSecondReservationWhenFirstOneUsesAvailableBalance() {
        UUID requestId = UUID.randomUUID();
        Curator curator = curator(new BigDecimal("1500"), new BigDecimal("1000"));
        arrangeNewReservation(requestId, curator);

        BalanceReservationResultEvent result = service.reserve(command(requestId));

        assertThat(result.status()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(result.availableBalance()).isEqualByComparingTo("500");
        assertThat(curator.getReservedTokens()).isEqualByComparingTo("1000");
    }

    @Test
    void repeatedCommandDoesNotReserveTwice() {
        UUID requestId = UUID.randomUUID();
        BalanceReservation existing = reservation(
                requestId,
                BalanceReservationStatus.RESERVED
        );
        when(reservationRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(existing));

        BalanceReservationResultEvent result = service.reserve(command(requestId));

        assertThat(result.status()).isEqualTo("RESERVED");
        verify(groupRepository, never()).findByVkGroupId(any());
        verify(curatorRepository, never()).findByIdForUpdate(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void releasesReservationOnlyOnce() {
        UUID requestId = UUID.randomUUID();
        Curator curator = curator(new BigDecimal("1500"), new BigDecimal("1000"));
        BalanceReservation reservation = reservation(
                requestId,
                BalanceReservationStatus.RESERVED,
                curator.getId()
        );
        when(reservationRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(reservation));
        when(curatorRepository.findByIdForUpdate(curator.getId()))
                .thenReturn(Optional.of(curator));

        service.release(new BalanceReleaseCommand(requestId, "AI failed"));

        assertThat(curator.getReservedTokens()).isZero();
        assertThat(reservation.getStatus()).isEqualTo(BalanceReservationStatus.RELEASED);
    }

    private void arrangeNewReservation(UUID requestId, Curator curator) {
        CuratorVkGroup group = CuratorVkGroup.builder()
                .curator(curator)
                .vkGroupId("100")
                .status(VkGroupStatus.ACTIVE)
                .build();
        when(reservationRepository.findByIdForUpdate(requestId))
                .thenReturn(Optional.empty(), Optional.empty());
        when(groupRepository.findByVkGroupId("100")).thenReturn(Optional.of(group));
        when(curatorRepository.findByIdForUpdate(curator.getId()))
                .thenReturn(Optional.of(curator));
    }

    private BalanceReservationCommand command(UUID requestId) {
        return new BalanceReservationCommand(
                requestId,
                "100",
                new BigDecimal("1000"),
                Instant.parse("2030-01-01T00:00:00Z")
        );
    }

    private Curator curator(BigDecimal balance, BigDecimal reserved) {
        return Curator.builder()
                .id(UUID.randomUUID())
                .balanceTokens(balance)
                .reservedTokens(reserved)
                .build();
    }

    private BalanceReservation reservation(
            UUID requestId,
            BalanceReservationStatus status
    ) {
        return reservation(requestId, status, UUID.randomUUID());
    }

    private BalanceReservation reservation(
            UUID requestId,
            BalanceReservationStatus status,
            UUID curatorId
    ) {
        return BalanceReservation.builder()
                .requestId(requestId)
                .curatorId(curatorId)
                .vkGroupId("100")
                .reservedCredits(new BigDecimal("1000"))
                .status(status)
                .balanceAtReservation(new BigDecimal("1500"))
                .availableBalanceAfter(new BigDecimal("500"))
                .createdAt(Instant.now())
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .build();
    }
}
