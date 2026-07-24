package ru.itmo.nemat.shared.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class KafkaRetryConfigurationTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private Consumer<String, String> consumer;
    @Mock
    private MessageListenerContainer container;

    private KafkaRetryConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = new KafkaRetryConfiguration();
    }

    @Test
    void publishesToDeadLetterTopicAfterConfiguredTotalAttempts() {
        DefaultErrorHandler handler =
                configuration.kafkaErrorHandler(kafkaTemplate, 0, 3);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("billing-charge-commands", 0, 42L, "key", "payload");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        new SendResult<>(null, null)
                ));

        handler.handleOne(failure(), record, consumer, container);
        handler.handleOne(failure(), record, consumer, container);
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

        handler.handleOne(failure(), record, consumer, container);

        ProducerRecord<String, String> recovered = capturedRecord();
        assertThat(recovered.topic()).isEqualTo("billing-charge-commands.DLT");
        assertThat(recovered.key()).isEqualTo("key");
        assertThat(recovered.value()).isEqualTo("payload");
    }

    @Test
    void parksRecordWhenDeadLetterConsumerAlsoFails() {
        DefaultErrorHandler handler =
                configuration.kafkaErrorHandler(kafkaTemplate, 0, 1);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("billing-charge-commands.DLT", 0, 7L, "key", "payload");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        new SendResult<>(null, null)
                ));

        handler.handleOne(failure(), record, consumer, container);

        assertThat(capturedRecord().topic())
                .isEqualTo("billing-charge-commands.DLT.PARKING");
    }

    @Test
    void rejectsConfigurationWithoutAnInitialAttempt() {
        assertThatThrownBy(() ->
                configuration.kafkaErrorHandler(kafkaTemplate, 1000, 0)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    private IllegalStateException failure() {
        return new IllegalStateException("consumer failed");
    }

    private ProducerRecord<String, String> capturedRecord() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }
}
