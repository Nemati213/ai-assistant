package ru.itmo.nemat.vkconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.vkconnector.dto.SendVkMessageCommand;
import ru.itmo.nemat.vkconnector.services.VkOutgoingDeliveryService;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VkOutgoingConsumerTest {

    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private VkOutgoingDeliveryService deliveryService;

    @InjectMocks
    private VkOutgoingConsumer consumer;

    @Test
    void delegatesDurableDeliveryProcessing() throws Exception {
        SendVkMessageCommand command = command();
        when(objectMapper.readValue("payload", SendVkMessageCommand.class)).thenReturn(command);

        consumer.consume("payload");

        verify(deliveryService).deliver(command);
    }

    private SendVkMessageCommand command() {
        return new SendVkMessageCommand(
                UUID.randomUUID(),
                "200",
                "100",
                "Answer",
                1
        );
    }
}
