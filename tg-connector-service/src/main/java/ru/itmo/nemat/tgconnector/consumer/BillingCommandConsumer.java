package ru.itmo.nemat.tgconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.dto.BillingChargeCommand;
import ru.itmo.nemat.tgconnector.service.BillingService;

@Component
@Slf4j
@RequiredArgsConstructor
public class BillingCommandConsumer {

    private final ObjectMapper objectMapper;
    private final BillingService billingService;

    @KafkaListener(topics = "billing-charge-commands", groupId = "tg-group")
    public void consume(String payload) {
        try {
            BillingChargeCommand command =
                    objectMapper.readValue(payload, BillingChargeCommand.class);
            billingService.charge(command);
        } catch (Exception exception) {
            log.error("Failed to process billing charge command", exception);
            throw new IllegalStateException("Failed to process billing charge command", exception);
        }
    }
}
