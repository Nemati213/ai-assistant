package ru.itmo.nemat.vkconnector.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.vkconnector.config.VkDeliveryRetryProperties;
import ru.itmo.nemat.vkconnector.dto.SendVkMessageCommand;
import ru.itmo.nemat.vkconnector.model.VkOutgoingDelivery;
import ru.itmo.nemat.vkconnector.model.VkOutgoingDeliveryStatus;
import ru.itmo.nemat.vkconnector.repository.VkOutgoingDeliveryRepository;

import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VkOutgoingDeliveryServiceTest {

    @Mock
    private VkOutgoingDeliveryRepository repository;
    @Mock
    private VkApiService vkApiService;

    @Test
    void storesSuccessfulResultForOutboxPublication() {
        VkOutgoingDeliveryService service = service();
        SendVkMessageCommand command = command(1);
        when(repository.findByIdForUpdate(command.requestId()))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(VkOutgoingDelivery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(vkApiService.sendMessage(command)).thenReturn(777L);

        service.deliver(command);

        ArgumentCaptor<VkOutgoingDelivery> captor =
                ArgumentCaptor.forClass(VkOutgoingDelivery.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(VkOutgoingDeliveryStatus.SUCCEEDED);
        assertThat(captor.getValue().getVkMessageId()).isEqualTo(777L);
        assertThat(captor.getValue().getDeliveryAttempt()).isEqualTo(1);
    }

    @Test
    void storesPermanentFailureInsteadOfRetrying() {
        VkOutgoingDeliveryService service = service();
        SendVkMessageCommand command = command(1);
        when(repository.findByIdForUpdate(command.requestId()))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(VkOutgoingDelivery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(vkApiService.sendMessage(command))
                .thenThrow(new VkApiException(901, "cannot send"));

        service.deliver(command);

        ArgumentCaptor<VkOutgoingDelivery> captor =
                ArgumentCaptor.forClass(VkOutgoingDelivery.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(VkOutgoingDeliveryStatus.FAILED);
        assertThat(captor.getValue().getDeliveryError())
                .contains("VK API error 901");
        assertThat(captor.getValue().getDeliveryErrorCategory())
                .isEqualTo("VK_API_901");
        assertThat(captor.getValue().getAutomaticRetryAttempts()).isZero();
    }

    @Test
    void schedulesAutomaticRetryForTemporaryVkError() {
        VkOutgoingDeliveryService service = service();
        SendVkMessageCommand command = command(1);
        when(repository.findByIdForUpdate(command.requestId()))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(VkOutgoingDelivery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(vkApiService.sendMessage(command))
                .thenThrow(new VkApiException(6, "too many requests"));

        service.deliver(command);

        ArgumentCaptor<VkOutgoingDelivery> captor =
                ArgumentCaptor.forClass(VkOutgoingDelivery.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(VkOutgoingDeliveryStatus.RETRY_PENDING);
        assertThat(captor.getValue().getDeliveryErrorCategory())
                .isEqualTo("VK_API_6");
        assertThat(captor.getValue().getAutomaticRetryAttempts()).isEqualTo(1);
        assertThat(captor.getValue().getNextDeliveryAttemptAt()).isNotNull();
        assertThat(captor.getValue().getResultPublishedAt()).isNull();
    }

    @Test
    void executesDueAutomaticRetryAndStoresSuccess() {
        VkOutgoingDeliveryService service = service();
        UUID requestId = UUID.randomUUID();
        VkOutgoingDelivery pending = failedDelivery(requestId, 1);
        pending.scheduleAutomaticRetry(
                "Temporary",
                "NETWORK",
                Instant.now().minusSeconds(1),
                Instant.now()
        );
        when(repository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(pending));
        SendVkMessageCommand command = command(requestId, 1);
        when(vkApiService.sendMessage(command)).thenReturn(888L);

        boolean retried = service.retryDue(requestId, Instant.now());

        assertThat(retried).isTrue();
        assertThat(pending.getStatus())
                .isEqualTo(VkOutgoingDeliveryStatus.SUCCEEDED);
        assertThat(pending.getVkMessageId()).isEqualTo(888L);
    }

    @Test
    void storesTerminalFailureAfterAutomaticRetriesAreExhausted() {
        VkOutgoingDeliveryService service = service();
        UUID requestId = UUID.randomUUID();
        Instant now = Instant.now();
        VkOutgoingDelivery processing = VkOutgoingDelivery.builder()
                .requestId(requestId)
                .vkChatId("200")
                .vkGroupId("100")
                .messageText("Answer")
                .deliveryAttempt(1)
                .automaticRetryAttempts(3)
                .status(VkOutgoingDeliveryStatus.PROCESSING)
                .createdAt(now)
                .updatedAt(now.minusSeconds(300))
                .nextPublishAt(now)
                .build();
        when(repository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(processing));
        when(vkApiService.sendMessage(command(requestId, 1)))
                .thenThrow(new VkApiException(6, "too many requests"));

        boolean recovered = service.recoverStaleProcessing(
                requestId,
                now.minusSeconds(120)
        );

        assertThat(recovered).isTrue();
        assertThat(processing.getStatus())
                .isEqualTo(VkOutgoingDeliveryStatus.FAILED);
        assertThat(processing.getAutomaticRetryAttempts()).isEqualTo(3);
        assertThat(processing.getDeliveryErrorCategory())
                .isEqualTo("VK_API_6");
    }

    @Test
    void ignoresDuplicateAttemptButAllowsVersionedRetry() {
        VkOutgoingDeliveryService service = service();
        UUID requestId = UUID.randomUUID();
        VkOutgoingDelivery existing = failedDelivery(requestId, 1);
        when(repository.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(existing));

        service.deliver(command(requestId, 1));

        verify(vkApiService, never()).sendMessage(any());

        SendVkMessageCommand retry = command(requestId, 2);
        when(vkApiService.sendMessage(retry)).thenReturn(888L);
        service.deliver(retry);

        verify(vkApiService).sendMessage(retry);
        assertThat(existing.getDeliveryAttempt()).isEqualTo(2);
        assertThat(existing.getStatus())
                .isEqualTo(VkOutgoingDeliveryStatus.SUCCEEDED);
        assertThat(existing.getVkMessageId()).isEqualTo(888L);
    }

    private SendVkMessageCommand command(int deliveryAttempt) {
        return command(UUID.randomUUID(), deliveryAttempt);
    }

    private SendVkMessageCommand command(UUID requestId, int deliveryAttempt) {
        return new SendVkMessageCommand(
                requestId,
                "200",
                "100",
                "Answer",
                deliveryAttempt
        );
    }

    private VkOutgoingDelivery failedDelivery(UUID requestId, int deliveryAttempt) {
        Instant now = Instant.now();
        return VkOutgoingDelivery.builder()
                .requestId(requestId)
                .vkChatId("200")
                .vkGroupId("100")
                .messageText("Answer")
                .deliveryAttempt(deliveryAttempt)
                .status(VkOutgoingDeliveryStatus.FAILED)
                .deliveryError("VK error")
                .createdAt(now)
                .updatedAt(now)
                .nextPublishAt(now)
                .build();
    }

    private VkOutgoingDeliveryService service() {
        VkDeliveryRetryProperties properties = new VkDeliveryRetryProperties();
        properties.setMaxAutomaticRetries(3);
        properties.setRetryBaseDelay(Duration.ofSeconds(2));
        properties.setRetryMaxDelay(Duration.ofSeconds(30));
        return new VkOutgoingDeliveryService(
                repository,
                vkApiService,
                new VkDeliveryFailureClassifier(),
                properties
        );
    }
}
