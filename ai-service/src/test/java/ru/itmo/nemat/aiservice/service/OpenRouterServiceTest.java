package ru.itmo.nemat.aiservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.itmo.nemat.aiservice.config.OpenRouterProperties;
import ru.itmo.nemat.aiservice.dto.AiGenerationCommand;
import ru.itmo.nemat.aiservice.dto.AiGenerationResult;
import ru.itmo.nemat.aiservice.dto.ConversationMessage;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenRouterServiceTest {

    @Test
    void sendsTextAndImagesAsMultimodalContent() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://openrouter.test/api/v1/chat/completions");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenRouterProperties properties = new OpenRouterProperties();
        properties.setModel("openai/gpt-4o-mini");
        OpenRouterService service = new OpenRouterService(
                builder.build(),
                properties
        );

        server.expect(requestTo("https://openrouter.test/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "model": "openai/gpt-4o-mini",
                          "messages": [
                            {
                              "role": "system",
                              "content": [
                                {"type": "text", "text": "Explain clearly"}
                              ]
                            },
                            {
                              "role": "user",
                              "content": [
                                {"type": "text", "text": "Previous question"}
                              ]
                            },
                            {
                              "role": "assistant",
                              "content": [
                                {"type": "text", "text": "Previous answer"}
                              ]
                            },
                            {
                              "role": "user",
                              "content": [
                                {"type": "text", "text": "Solve this"},
                                {
                                  "type": "image_url",
                                  "image_url": {"url": "https://vk.test/photo-1.jpg"}
                                },
                                {
                                  "type": "image_url",
                                  "image_url": {"url": "https://vk.test/photo-2.jpg"}
                                }
                              ]
                            }
                          ]
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {"message": {"role": "assistant", "content": "Answer"}}
                          ],
                          "usage": {
                            "total_tokens": 500,
                            "cost": 0.005
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        UUID requestId = UUID.randomUUID();
        AiGenerationResult result = service.generate(new AiGenerationCommand(
                requestId,
                "200",
                "300",
                "100",
                "Solve this",
                List.of(
                        "https://vk.test/photo-1.jpg",
                        "",
                        "https://vk.test/photo-2.jpg"
                ),
                "Explain clearly"
        ), List.of(
                new ConversationMessage("user", "Previous question"),
                new ConversationMessage("assistant", "Previous answer")
        ));

        server.verify();
        assertThat(result.answerText()).isEqualTo("Answer");
        assertThat(result.tokensUsed()).isEqualTo(500);
        assertThat(result.providerCostUsd())
                .isEqualByComparingTo(new BigDecimal("0.005"));
    }
}
