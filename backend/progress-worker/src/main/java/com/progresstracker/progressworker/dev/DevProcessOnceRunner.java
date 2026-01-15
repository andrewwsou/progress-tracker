package com.progresstracker.progressworker.dev;

import com.progresstracker.progressworker.service.CompletionProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class DevProcessOnceRunner implements CommandLineRunner {

    private final CompletionProcessor completionProcessor;

    @Value("${dev.processOnce:false}")
    private boolean enabled;

    @Value("${dev.userId:0}")
    private long userId;

    @Value("${dev.habitId:0}")
    private long habitId;

    @Value("${dev.date:}")
    private String dateStr;

    public DevProcessOnceRunner(CompletionProcessor completionProcessor) {
        this.completionProcessor = completionProcessor;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;

        if (userId <= 0 || habitId <= 0 || dateStr == null || dateStr.isBlank()) {
            throw new IllegalStateException("dev.processOnce requires dev.userId, dev.habitId, dev.date=YYYY-MM-DD");
        }

        LocalDate date = LocalDate.parse(dateStr);
        completionProcessor.process(userId, habitId, date);

        System.out.println("DEV_PROCESS_ONCE done userId=" + userId + " habitId=" + habitId + " date=" + date);
    }
}
