package ru.itmo.nemat.tgconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.itmo.nemat.shared.kafka.KafkaCorrelationHeaders;
import ru.itmo.nemat.shared.kafka.KafkaMdcRecordInterceptor;
import ru.itmo.nemat.shared.kafka.KafkaRetryConfiguration;
import ru.itmo.nemat.tgconnector.dto.BillingChargeCommand;
import ru.itmo.nemat.tgconnector.dto.BillingResultEvent;
import ru.itmo.nemat.tgconnector.service.BillingService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = BillingCommandKafkaIT.TestApplication.class,
        properties = {
                "spring.main.web-application-type=none",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.kafka.consumer.enable-auto-commit=false",
                "spring.kafka.consumer.key-deserializer="
                        + "org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.value-deserializer="
                        + "org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.producer.key-serializer="
                        + "org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer="
                        + "org.apache.kafka.common.serialization.StringSerializer",
                "app.kafka.retry.interval-ms=25",
                "app.kafka.retry.max-attempts=3"
        }
)
@EmbeddedKafka(
        partitions = 1,
        topics = {
                BillingCommandKafkaIT.COMMAND_TOPIC,
                BillingCommandKafkaIT.DLT_TOPIC
        }
)
@DirtiesContext
class BillingCommandKafkaIT {

    static final String COMMAND_TOPIC = "billing-charge-commands";
    static final String DLT_TOPIC = COMMAND_TOPIC + ".DLT";

    @SpringBootConfiguration
    @ImportAutoConfiguration({
            JacksonAutoConfiguration.class,
            KafkaAutoConfiguration.class
    })
    @Import({
            KafkaRetryConfiguration.class,
            BillingCommandConsumer.class
    })
    static class TestApplication {
    }

    @MockitoBean
    private BillingService billingService;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    BillingCommandKafkaIT(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            EmbeddedKafkaBroker embeddedKafka
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.embeddedKafka = embeddedKafka;
    }

    @Test
    void validCommandReachesBillingWithRequestIdInMdc() throws Exception {
        BillingChargeCommand command = command(UUID.randomUUID());
        AtomicReference<String> observedRequestId = new AtomicReference<>();
        when(billingService.charge(any())).thenAnswer(invocation -> {
            observedRequestId.set(MDC.get("requestId"));
            return chargedResult(invocation.getArgument(0));
        });

        send(command.requestId(), objectMapper.writeValueAsString(command));

        verify(billingService, timeout(10_000)).charge(command);
        assertThat(observedRequestId).hasValue(command.requestId().toString());
    }

    @Test
    void businessFailureIsRetriedThreeTimesAndPublishedToDlt() throws Exception {
        BillingChargeCommand command = command(UUID.randomUUID());
        when(billingService.charge(any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        send(command.requestId(), objectMapper.writeValueAsString(command));

        ConsumerRecord<String, String> deadLetter =
                awaitRecord(DLT_TOPIC, command.requestId().toString());
        verify(billingService, timeout(10_000).times(3)).charge(command);
        assertThat(deadLetter.value()).isEqualTo(objectMapper.writeValueAsString(command));
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC))
                .isEqualTo(COMMAND_TOPIC);
        assertThat(header(deadLetter, KafkaMdcRecordInterceptor.REQUEST_ID_HEADER))
                .isEqualTo(command.requestId().toString());
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .contains("Failed to process billing charge command");
    }

    @Test
    void malformedJsonIsPublishedToDltWithoutCallingBilling() throws Exception {
        UUID requestId = UUID.randomUUID();

        send(requestId, "{not-valid-json");

        ConsumerRecord<String, String> deadLetter =
                awaitRecord(DLT_TOPIC, requestId.toString());
        verify(billingService, never()).charge(any());
        assertThat(deadLetter.value()).isEqualTo("{not-valid-json");
        assertThat(header(deadLetter, KafkaMdcRecordInterceptor.REQUEST_ID_HEADER))
                .isEqualTo(requestId.toString());
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .contains("Failed to process billing charge command");
    }

    private void send(UUID requestId, String payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                COMMAND_TOPIC,
                requestId.toString(),
                payload
        );
        KafkaCorrelationHeaders.addRequestId(record, requestId);
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private ConsumerRecord<String, String> awaitRecord(
            String topic,
            String expectedKey
    ) {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(
                "probe-" + UUID.randomUUID(),
                "false",
                embeddedKafka
        );
        try (Consumer<String, String> consumer =
                     new DefaultKafkaConsumerFactory<>(
                             properties,
                             new StringDeserializer(),
                             new StringDeserializer()
                     ).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, topic);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records =
                        consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, String> record : records) {
                    if (expectedKey.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError(
                "Timed out waiting for key " + expectedKey + " in topic " + topic
        );
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header)
                .as("Kafka header %s", name)
                .isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private BillingChargeCommand command(UUID requestId) {
        return new BillingChargeCommand(
                requestId,
                "100",
                30,
                new BigDecimal("0.00015"),
                new BigDecimal("100"),
                new BigDecimal("200000"),
                new BigDecimal("100")
        );
    }

    private BillingResultEvent chargedResult(BillingChargeCommand command) {
        return new BillingResultEvent(
                command.requestId(),
                "CHARGED",
                command.creditsToCharge(),
                new BigDecimal("400"),
                null
        );
    }
}
