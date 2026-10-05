package com.progresstracker.progresstracker.web;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The error paths that are hard to reach through the real API: database refusals and unexpected failures. */
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void anUnexpectedFailureIsA500ThatDoesNotLeakInternals() throws Exception {
        mvc.perform(get("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("secret-internal-detail"))));
    }

    @Test
    void aBrokenConstraintIsA409ThatDoesNotLeakTheRow() throws Exception {
        mvc.perform(get("/constraint"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The request conflicts with existing data."))
                .andExpect(content().string(not(containsString("a@b.c"))));
    }

    @Test
    void aBrokenConstraintIsLoggedByNameWithoutTheRowsValues(CapturedOutput output) throws Exception {
        mvc.perform(get("/constraint")).andExpect(status().isConflict());

        // The driver's message names the email address; the constraint's name is enough to debug with.
        assertThat(output).contains("constraint uk_email").doesNotContain("a@b.c");
    }

    @Test
    void dataTheDatabaseCannotStoreIsA400() throws Exception {
        mvc.perform(get("/bad-data"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The request contains data that cannot be stored."));
    }

    @RestController
    static class FailingController {

        @GetMapping("/unexpected")
        String unexpected() {
            throw new IllegalStateException("secret-internal-detail");
        }

        @GetMapping("/constraint")
        String constraint() {
            throw new DataIntegrityViolationException("could not execute statement",
                    new ConstraintViolationException("duplicate key (email)=(a@b.c)",
                            new SQLException("duplicate key value violates unique constraint \"uk_email\": "
                                    + "Key (email)=(a@b.c) already exists."),
                            "uk_email"));
        }

        @GetMapping("/bad-data")
        String badData() {
            throw new DataIntegrityViolationException("could not execute statement",
                    new DataException("invalid byte sequence", new SQLException()));
        }
    }
}
