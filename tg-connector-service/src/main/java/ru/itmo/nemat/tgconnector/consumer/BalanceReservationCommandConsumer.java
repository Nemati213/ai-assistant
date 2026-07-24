package ru.itmo.nemat.tgconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.dto.BalanceReservationCommand;
import ru.itmo.nemat.tgconnector.dto.BalanceReservationResultEvent;
import ru.itmo.nemat.tgconnector.producer.BalanceReservationResultProducer;
import ru.itmo.nemat.tgconnector.service.BalanceReservationService;

@Component
@Slf4j
@RequiredArgsConstructor
public class BalanceReservationCommandConsumer {

    private final ObjectMapper objectMapper;
    private final BalanceReservationService reservationService;
    private final BalanceReservationResultProducer resultProducer;

    @KafkaListener(topics = "balance-reservation-commands", groupId = "tg-group")
    public void consume(String payload) {
        try {
            BalanceReservationCommand command =
                    objectMapper.readValue(payload, BalanceReservationCommand.class);
            BalanceReservationResultEvent result = reservationService.reserve(command);
            resultProducer.send(result);
        } catch (Exception exception) {
            log.error("Failed to process balance reservation command", exception);
            throw new IllegalStateException(
                    "Failed to process balance reservation command",
                    exception
            );
        }
    }
}
