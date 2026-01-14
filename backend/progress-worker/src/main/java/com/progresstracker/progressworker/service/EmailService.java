package com.progresstracker.progressworker.service;

import com.progresstracker.progressworker.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    public void queueCompletionEmail(User user, String habitName) {
        log.info("EMAIL_QUEUED userId={} email={} habit={}", user.getId(), user.getEmail(), habitName);
    }
}
