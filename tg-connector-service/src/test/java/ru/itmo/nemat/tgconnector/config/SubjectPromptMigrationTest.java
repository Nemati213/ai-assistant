package ru.itmo.nemat.tgconnector.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectPromptMigrationTest {

    private static final String MIGRATION =
            "db/migration/V14__expand_subjects_and_refresh_prompts.sql";

    @Test
    void containsEverySupportedSubjectAndVkFormattingRules() throws IOException {
        String sql;
        try (var stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains(
                "'MATH'",
                "'PHYSICS'",
                "'BIOLOGY'",
                "'RUSSIAN'",
                "'LITERATURE'",
                "'ENGLISH'",
                "'GEOGRAPHY'",
                "'INFORMATICS'",
                "'CHEMISTRY'",
                "'HISTORY'",
                "'SOCIAL_STUDIES'"
        );
        assertThat(sql).contains(
                "Никогда не используй LaTeX",
                "Формулы пиши обычно: v = s/t",
                "H2SO4",
                "Не упоминай, что ты ИИ"
        );
    }
}
