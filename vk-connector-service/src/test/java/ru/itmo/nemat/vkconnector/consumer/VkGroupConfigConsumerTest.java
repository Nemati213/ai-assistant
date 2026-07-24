package ru.itmo.nemat.vkconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.shared.security.SecretCipher;
import ru.itmo.nemat.vkconnector.dto.VkGroupConfigEvent;
import ru.itmo.nemat.vkconnector.dto.VkGroupConfigStatusEvent;
import ru.itmo.nemat.vkconnector.services.VkGroupCredentialsService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VkGroupConfigConsumerTest {

    @Mock
    private VkGroupCredentialsService credentialsService;
    @Test
    void decryptsKafkaSecretsBeforeConfiguringGroup() throws Exception {
        SecretCipher cipher = cipher();
        ObjectMapper objectMapper = new ObjectMapper();
        VkGroupConfigConsumer consumer = new VkGroupConfigConsumer(
                objectMapper,
                credentialsService,
                cipher
        );
        UUID eventId = UUID.randomUUID();
        VkGroupConfigEvent encrypted = new VkGroupConfigEvent(
                eventId,
                1L,
                "UPSERT",
                "100",
                cipher.encrypt("token"),
                cipher.encrypt("secret"),
                cipher.encrypt("confirmation"),
                "prompt"
        );
        when(credentialsService.process(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new VkGroupConfigStatusEvent(
                        eventId,
                        1L,
                        "100",
                        "ACTIVE",
                        null
                ));

        consumer.consume(objectMapper.writeValueAsString(encrypted));

        ArgumentCaptor<VkGroupConfigEvent> eventCaptor =
                ArgumentCaptor.forClass(VkGroupConfigEvent.class);
        verify(credentialsService).process(eventCaptor.capture());
        assertThat(eventCaptor.getValue().vkToken()).isEqualTo("token");
        assertThat(eventCaptor.getValue().vkSecret()).isEqualTo("secret");
        assertThat(eventCaptor.getValue().vkConfirmationCode()).isEqualTo("confirmation");
    }

    @Test
    void rejectsPlaintextSecretsFromKafka() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        VkGroupConfigConsumer consumer = new VkGroupConfigConsumer(
                objectMapper,
                credentialsService,
                cipher()
        );
        VkGroupConfigEvent plaintext = new VkGroupConfigEvent(
                UUID.randomUUID(),
                1L,
                "UPSERT",
                "100",
                "token",
                "secret",
                "confirmation",
                "prompt"
        );

        assertThatThrownBy(() -> consumer.consume(
                objectMapper.writeValueAsString(plaintext)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to process group config");
    }

    private SecretCipher cipher() {
        return new SecretCipher(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
        ));
    }
}
