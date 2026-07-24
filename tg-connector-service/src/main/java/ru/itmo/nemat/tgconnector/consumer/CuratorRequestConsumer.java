package ru.itmo.nemat.tgconnector.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.bot.CuratorTelegramBot;
import ru.itmo.nemat.tgconnector.dto.CuratorApprovalRequest;

@Component
@Slf4j
@RequiredArgsConstructor
public class CuratorRequestConsumer {

    private final ObjectMapper objectMapper;
    private final CuratorTelegramBot curatorTelegramBot;

    @KafkaListener(topics = "curator-approval-requests", groupId = "tg-group")
    public void consume(String messageJson) {
        try {
            CuratorApprovalRequest request = objectMapper.readValue(messageJson, CuratorApprovalRequest.class);
            curatorTelegramBot.sendApprovalRequest(request);
        } catch (Exception e) {
            log.error("Failed to process curator approval request", e);
            throw new IllegalStateException("Failed to process curator approval request", e);
        }
    }
}
