package com.progresstracker.progressworker.service;

import com.progresstracker.progressworker.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** What the email notices write to the log: the user id, never the address, and one line each. */
@ExtendWith(OutputCaptureExtension.class)
class EmailServiceTest {

    private final EmailService emailService = new EmailService();

    @Test
    void theCompletionNoticeLogsTheUserIdButNotTheEmailAddress(CapturedOutput output) {
        User user = new User();
        user.setId(42L);
        user.setEmail("someone@example.com");

        emailService.queueCompletionEmail(user, "Read");

        assertThat(output.getOut()).contains("EMAIL_NOTICE userId=42 kind=completion habit=\"Read\"");
        assertThat(output.getAll()).doesNotContain("someone@example.com");
    }

    @Test
    void aHabitNameCannotStartALogLineOfItsOwn(CapturedOutput output) {
        User user = new User();
        user.setId(42L);
        String forged = "Run\n2026-10-05T13:00:00.000Z ERROR 1 --- [sqs-poller] Purging dead-letter queue";

        emailService.queueCompletionEmail(user, forged);
        emailService.queueWeeklySummaryEmail(42L, "Great week\r\nERROR forged\u2028line");

        assertThat(output.getOut()).contains("habit=\"Run?2026-10-05T13:00:00.000Z ERROR 1");
        assertThat(output.getOut()).contains("headline=\"Great week??ERROR forged?line\"");
        assertThat(output.getOut().lines()).noneMatch(line -> line.startsWith("2026-10-05T13:00:00.000Z ERROR"));
        assertThat(output.getOut().lines()).noneMatch(line -> line.startsWith("ERROR forged"));
    }

    @Test
    void oneLineReplacesEveryLineBreakAndControlCharacter() {
        assertThat(EmailService.oneLine("a\nb\rc\td\u0000e\u0085f\u2028g\u2029h")).isEqualTo("a?b?c?d?e?f?g?h");
        assertThat(EmailService.oneLine("Plan the week")).isEqualTo("Plan the week");
        assertThat(EmailService.oneLine(null)).isNull();
    }
}
