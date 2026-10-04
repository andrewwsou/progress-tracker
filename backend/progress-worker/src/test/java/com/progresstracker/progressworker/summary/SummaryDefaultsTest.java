package com.progresstracker.progressworker.summary;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The defaults that keep Claude from being called, or from costing much, by accident. */
class SummaryDefaultsTest {

    @Test
    void withNothingConfiguredClaudeIsOffAndCapped() {
        SummaryProperties.Llm defaults = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("summary", SummaryProperties.class).llm();

        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.maxRetries()).isZero();
        assertThat(defaults.maxCallsPerDay()).isEqualTo(10);
        assertThat(defaults.dailyTokenBudget()).isEqualTo(100_000);
        assertThat(defaults.maxPromptBytes()).isEqualTo(8_000);
    }

    @Test
    void aNegativeLimitIsRefusedRatherThanTurningACapOff() {
        Binder binder = new Binder(new MapConfigurationPropertySource(java.util.Map.of("summary.llm.max-retries", "-1")));

        assertThatThrownBy(() -> binder.bindOrCreate("summary", SummaryProperties.class))
                .rootCause().hasMessageContaining("must not be negative");
    }

    @Test
    void applicationYamlKeepsClaudeOffUnlessTheFlagIsSet() throws IOException {
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml")).get(0);

        assertThat(yaml.getProperty("summary.llm.enabled")).hasToString("${SUMMARY_LLM_ENABLED:false}");
        assertThat(yaml.getProperty("summary.llm.max-retries")).hasToString("0");
        assertThat(yaml.getProperty("summary.llm.max-calls-per-day")).hasToString("${SUMMARY_LLM_MAX_CALLS_PER_DAY:10}");
        assertThat(yaml.getProperty("summary.llm.daily-token-budget")).hasToString("100000");
    }
}
