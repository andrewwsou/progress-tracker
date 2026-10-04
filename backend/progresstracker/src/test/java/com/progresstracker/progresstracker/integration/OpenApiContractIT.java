package com.progresstracker.progresstracker.integration;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the committed API contract honest. {@code openapi.json} is what the frontend's
 * TypeScript types are generated from, so it must always describe the API as it really is.
 *
 * If you changed the API on purpose, regenerate the file and commit it:
 * <pre>./mvnw verify -Dopenapi.update=true</pre>
 */
@TestPropertySource(properties = "queue.enabled=false")
class OpenApiContractIT extends IntegrationTestBase {

    private static final Path COMMITTED_SPEC = Path.of("openapi.json");

    @Test
    void committedSpecMatchesTheRunningApi() throws Exception {
        String live = render(rest.getForObject("/v3/api-docs", JsonNode.class));

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(COMMITTED_SPEC, live);
            return;
        }

        assertThat(live)
                .as("openapi.json is out of date. Regenerate it with: ./mvnw verify -Dopenapi.update=true")
                .isEqualToNormalizingNewlines(Files.readString(COMMITTED_SPEC));
    }

    @Test
    void theContractAndItsUiArePublicButInternalEndpointsAreNotDocumented() {
        JsonNode spec = rest.getForObject("/v3/api-docs", JsonNode.class);

        assertThat(spec.get("paths").has("/api/habits")).isTrue();
        assertThat(spec.get("paths").fieldNames())
                .toIterable()
                .noneMatch(path -> path.startsWith("/api/internal"));
        assertThat(rest.getForEntity("/swagger-ui/index.html", String.class).getStatusCode().value()).isEqualTo(200);
    }

    /** Same input always renders to the same text: sorted keys, two-space indent, LF line endings. */
    private static String render(JsonNode spec) throws Exception {
        ((ObjectNode) spec).remove("servers"); // host and port differ on every run

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(indenter)
                .withArrayIndenter(indenter);

        Object asPlainMaps = mapper.treeToValue(spec, Object.class);
        return mapper.writer(printer).writeValueAsString(asPlainMaps) + "\n";
    }
}
