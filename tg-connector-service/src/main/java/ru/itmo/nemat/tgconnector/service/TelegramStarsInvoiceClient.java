package ru.itmo.nemat.tgconnector.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import ru.itmo.nemat.tgconnector.config.TelegramStarsProperties;

import java.util.List;

@Service
@Slf4j
public class TelegramStarsInvoiceClient {

    private final String apiUrl;
    private final TelegramStarsProperties properties;
    private final TelegramRateLimiter rateLimiter;
    private final RestTemplate restTemplate;

    public TelegramStarsInvoiceClient(
            @Value("${telegram.bot-token}") String botToken,
            TelegramStarsProperties properties,
            TelegramRateLimiter rateLimiter
    ) {
        this.apiUrl = "https://api.telegram.org/bot" + botToken + "/sendInvoice";
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.restTemplate = new RestTemplate();
    }

    public void sendProInvoice(Long tgChatId) {
        InvoiceRequest request = new InvoiceRequest(
                tgChatId.toString(),
                "Pro",
                "300 000 credits для ответов куратора",
                properties.getProductPayload(),
                "",
                "XTR",
                List.of(new Price("Pro", properties.getPrice()))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rateLimiter.acquire();
        TelegramResponse response = restTemplate.postForObject(
                apiUrl,
                new HttpEntity<>(request, headers),
                TelegramResponse.class
        );
        if (response == null || !response.ok()) {
            String description = response == null ? "empty response" : response.description();
            throw new IllegalStateException("Telegram invoice failed: " + description);
        }
        log.info("Telegram Stars invoice sent to chat {}", tgChatId);
    }

    private record InvoiceRequest(
            String chat_id,
            String title,
            String description,
            String payload,
            String provider_token,
            String currency,
            List<Price> prices
    ) {
    }

    private record Price(String label, int amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TelegramResponse(boolean ok, String description) {
    }
}
