package ru.itmo.nemat.aiservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.itmo.nemat.aiservice.config.OpenRouterProperties;
import ru.itmo.nemat.aiservice.dto.AiGenerationCommand;
import ru.itmo.nemat.aiservice.dto.AiGenerationResult;
import ru.itmo.nemat.aiservice.dto.ConversationMessage;
import ru.itmo.nemat.aiservice.dto.openrouter.ChatMessage;
import ru.itmo.nemat.aiservice.dto.openrouter.ContentPart;
import ru.itmo.nemat.aiservice.dto.openrouter.ImageUrl;
import ru.itmo.nemat.aiservice.dto.openrouter.OpenRouterRequest;
import ru.itmo.nemat.aiservice.dto.openrouter.OpenRouterResponse;
import ru.itmo.nemat.aiservice.dto.openrouter.Usage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OpenRouterService {

    private final RestClient openRouterRestClient;
    private final OpenRouterProperties properties;

    public AiGenerationResult generate(
            AiGenerationCommand command,
            List<ConversationMessage> history
    ) {
        try {
            String promptText = command.systemPrompt() != null && !command.systemPrompt().isBlank()
                    ? command.systemPrompt()
                    : "You are a helpful teaching assistant.";

            ChatMessage systemMessage = new ChatMessage(
                    "system",
                    List.of(new ContentPart("text", promptText, null))
            );

            List<ContentPart> userContent = new ArrayList<>();
            userContent.add(new ContentPart("text", command.questionText(), null));
            if (command.photoUrls() != null) {
                for (String url : command.photoUrls()) {
                    if (url != null && !url.isBlank()) {
                        userContent.add(new ContentPart("image_url", null, new ImageUrl(url)));
                    }
                }
            }

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(systemMessage);
            if (history != null) {
                history.stream()
                        .filter(message -> message != null
                                && message.role() != null
                                && message.text() != null
                                && !message.text().isBlank())
                        .map(message -> new ChatMessage(
                                message.role(),
                                List.of(new ContentPart(
                                        "text",
                                        message.text(),
                                        null
                                ))
                        ))
                        .forEach(messages::add);
            }
            messages.add(new ChatMessage("user", userContent));

            OpenRouterRequest request = new OpenRouterRequest(
                    properties.getModel(),
                    messages
            );

            OpenRouterResponse response = openRouterRestClient.post()
                    .body(request)
                    .retrieve()
                    .body(OpenRouterResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new IllegalStateException("OpenRouter returned an empty response");
            }
            if (response.choices().get(0).message() == null
                    || response.choices().get(0).message().content() == null
                    || response.choices().get(0).message().content().isBlank()) {
                throw new IllegalStateException("OpenRouter returned an empty answer");
            }

            Integer tokensUsed = response.usage() != null
                    && response.usage().totalTokens() != null
                    ? response.usage().totalTokens()
                    : 0;
            if (tokensUsed < 0) {
                throw new IllegalStateException("OpenRouter returned negative token usage");
            }
            BigDecimal providerCostUsd = extractProviderCost(response.usage());

            log.info(
                    "[{}] AI response received: {} tokens, provider cost ${}",
                    command.requestId(),
                    tokensUsed,
                    providerCostUsd
            );

            return new AiGenerationResult(
                    response.choices().get(0).message().content(),
                    tokensUsed,
                    providerCostUsd
            );
        } catch (Exception exception) {
            log.error("[{}] OpenRouter generation failed", command.requestId(), exception);
            throw new IllegalStateException("OpenRouter generation failed", exception);
        }
    }

    private BigDecimal extractProviderCost(Usage usage) {
        if (usage == null || usage.cost() == null) {
            throw new IllegalStateException("OpenRouter response does not contain usage.cost");
        }
        if (usage.cost().signum() < 0) {
            throw new IllegalStateException("OpenRouter returned negative usage.cost");
        }
        return usage.cost();
    }
}
