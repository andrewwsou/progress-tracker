package com.progresstracker.progresstracker.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which browser origins may call the API: by default the Vite dev server and `npm run preview`. */
@TestPropertySource(properties = "queue.enabled=false")
class CorsIT extends IntegrationTestBase {

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://localhost:4173"})
    void theFrontendsOriginsMayCallTheApi(String origin) {
        ResponseEntity<String> preflight = preflight(origin);

        assertThat(preflight.getStatusCode().value()).isEqualTo(200);
        assertThat(preflight.getHeaders().getAccessControlAllowOrigin()).isEqualTo(origin);
    }

    @Test
    void otherOriginsMayNot() {
        ResponseEntity<String> preflight = preflight("https://elsewhere.example");

        assertThat(preflight.getStatusCode().value()).isEqualTo(403);
        assertThat(preflight.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    private ResponseEntity<String> preflight(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(HttpMethod.POST);
        headers.setAccessControlRequestHeaders(List.of("Authorization", "Content-Type"));
        return rest.exchange("/api/habits", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }
}
