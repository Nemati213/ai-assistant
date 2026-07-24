package ru.itmo.nemat.tgconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.bot.CuratorTelegramBot;
import ru.itmo.nemat.tgconnector.dto.CuratorIntakeRequest;

@Component
@Slf4j
@RequiredArgsConstructor
public class CuratorIntakeRequestConsumer {

    private final ObjectMapper objectMapper;
    private final CuratorTelegramBot curatorTelegramBot;

    @KafkaListener(topics = "curator-intake-requests", groupId = "tg-group")
    public void consume(String messageJson) {
        try {
            CuratorIntakeRequest request =
                    objectMapper.readValue(messageJson, CuratorIntakeRequest.class);
            curatorTelegramBot.sendIntakeRequest(request);
        } catch (Exception exception) {
            log.error("Failed to process curator intake request", exception);
            throw new IllegalStateException(
                    "Failed to process curator intake request",
                    exception
            );
        }
    }
}
