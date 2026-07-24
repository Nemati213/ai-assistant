package ru.itmo.nemat.tgconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.dto.BalanceReleaseCommand;
import ru.itmo.nemat.tgconnector.service.BalanceReservationService;

@Component
@Slf4j
@RequiredArgsConstructor
public class BalanceReleaseCommandConsumer {

    private final ObjectMapper objectMapper;
    private final BalanceReservationService reservationService;

    @KafkaListener(topics = "balance-release-commands", groupId = "tg-group")
    public void consume(String payload) {
        try {
            BalanceReleaseCommand command =
                    objectMapper.readValue(payload, BalanceReleaseCommand.class);
            reservationService.release(command);
        } catch (Exception exception) {
            log.error("Failed to process balance release command", exception);
            throw new IllegalStateException(
                    "Failed to process balance release command",
                    exception
            );
        }
    }
}
