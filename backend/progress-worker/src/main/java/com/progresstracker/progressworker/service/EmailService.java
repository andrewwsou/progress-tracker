package com.progresstracker.progressworker.service;

import com.progresstracker.progressworker.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Notes the emails a user would get. No mail provider is connected yet, so each one is a log line.
 * The line carries the user id, never the address, and the text the user typed is kept to one line.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    /** Control characters (line breaks included) and the Unicode line and paragraph separators. */
    private static final Pattern LINE_BREAKING = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");

    public void queueCompletionEmail(User user, String habitName) {
        log.info("EMAIL_NOTICE userId={} kind=completion habit=\"{}\"", user.getId(), oneLine(habitName));
    }

    public void queueWeeklySummaryEmail(long userId, String headline) {
        log.info("EMAIL_NOTICE userId={} kind=weekly-summary headline=\"{}\"", userId, oneLine(headline));
    }

    /** Text the user typed can hold a line break, which would let it forge a log line of its own. */
    static String oneLine(String text) {
        return text == null ? null : LINE_BREAKING.matcher(text).replaceAll("?");
    }
}
