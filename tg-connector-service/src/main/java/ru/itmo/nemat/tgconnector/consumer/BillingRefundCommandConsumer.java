package ru.itmo.nemat.tgconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.dto.BillingRefundCommand;
import ru.itmo.nemat.tgconnector.service.BillingRefundService;

@Component
@Slf4j
@RequiredArgsConstructor
public class BillingRefundCommandConsumer {

    private final ObjectMapper objectMapper;
    private final BillingRefundService refundService;

    @KafkaListener(topics = "billing-refund-commands", groupId = "tg-group")
    public void consume(String payload) {
        try {
            BillingRefundCommand command =
                    objectMapper.readValue(payload, BillingRefundCommand.class);
            refundService.refund(command);
        } catch (Exception exception) {
            log.error("Failed to process billing refund command", exception);
            throw new IllegalStateException("Failed to process billing refund command", exception);
        }
    }
}
