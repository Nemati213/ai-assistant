package ru.itmo.nemat.tgconnector.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.itmo.nemat.shared.security.SecretCipher;
import ru.itmo.nemat.tgconnector.dto.VkGroupConfigEvent;
import ru.itmo.nemat.tgconnector.model.VkGroupConfigOutboxEvent;
import ru.itmo.nemat.tgconnector.repository.VkGroupConfigOutboxRepository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VkGroupConfigProducerTest {

    @Mock
    private VkGroupConfigOutboxRepository repository;

    @Test
    void encryptsSecretsBeforePublishingToKafka() {
        SecretCipher cipher = cipher();
        VkGroupConfigProducer producer = new VkGroupConfigProducer(
                repository,
                new ObjectMapper(),
                cipher
        );
        UUID eventId = UUID.randomUUID();

        producer.sendConfig(new VkGroupConfigEvent(
                eventId,
                5L,
                "UPSERT",
                "100",
                "token",
                "secret",
                "confirmation",
                "prompt"
        ));

        ArgumentCaptor<VkGroupConfigOutboxEvent> captor =
                ArgumentCaptor.forClass(VkGroupConfigOutboxEvent.class);
        verify(repository).save(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).doesNotContain("\"token\"", "\"secret\"", "\"confirmation\"");
        assertThat(payload).contains("enc:v1:");
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
        assertThat(captor.getValue().getConfigVersion()).isEqualTo(5L);
    }

    private SecretCipher cipher() {
        return new SecretCipher(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
        ));
    }
}
