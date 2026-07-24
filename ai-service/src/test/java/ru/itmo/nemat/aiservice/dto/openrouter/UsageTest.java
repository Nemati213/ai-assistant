package ru.itmo.nemat.aiservice.dto.openrouter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsProviderCostFromOpenRouterUsage() throws Exception {
        Usage usage = objectMapper.readValue(
                """
                        {
                          "total_tokens": 1234,
                          "cost": 0.00567
                        }
                        """,
                Usage.class
        );

        assertThat(usage.totalTokens()).isEqualTo(1234);
        assertThat(usage.cost()).isEqualByComparingTo("0.00567");
    }
}
