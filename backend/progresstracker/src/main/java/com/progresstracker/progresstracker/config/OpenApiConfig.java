package com.progresstracker.progresstracker.config;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;

/**
 * The API's contract, served at /v3/api-docs and browsable at /swagger-ui.html.
 * The frontend's TypeScript types are generated from it.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Progress Tracker API",
                version = "1.0",
                description = "Habits, completions, streaks, XP, and achievements."),
        security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
@SecurityScheme(
        name = OpenApiConfig.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    private static final String PROBLEM_SCHEMA = "ProblemDetail";
    private static final String PROBLEM_JSON = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    /** Registers the error body once, described the way it is actually serialized. */
    @Bean
    OpenApiCustomizer problemDetailSchema() {
        return openApi -> {
            Schema<?> problem = ModelConverters.getInstance().read(ProblemDetail.class).get(PROBLEM_SCHEMA);
            // Extension members are written at the top level of the document, not under "properties".
            problem.getProperties().remove("properties");
            problem.addProperty("errors", new MapSchema()
                    .additionalProperties(new StringSchema())
                    .description("Per-field validation messages. Present on validation failures only."));
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problem);
        };
    }

    /** Documents the error responses each operation can actually produce. */
    @Bean
    OperationCustomizer errorResponses() {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses();

            if (operation.getRequestBody() != null) {
                addProblem(responses, "400", "The request body is malformed or fails validation");
            }
            boolean requiresToken = operation.getSecurity() == null || !operation.getSecurity().isEmpty();
            if (requiresToken) {
                addProblem(responses, "401", "Missing or invalid bearer token");
            }
            boolean targetsOneHabit = operation.getParameters() != null
                    && operation.getParameters().stream().anyMatch(p -> "path".equals(p.getIn()));
            if (targetsOneHabit) {
                addProblem(responses, "403", "The habit belongs to another user");
                addProblem(responses, "404", "No such habit");
            }
            return operation;
        };
    }

    private static void addProblem(ApiResponses responses, String status, String description) {
        if (responses.containsKey(status)) {
            return;
        }
        Schema<?> schema = new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA);
        responses.addApiResponse(status, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(schema))));
    }
}
