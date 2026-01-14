package com.progresstracker.progresstracker.config;

import com.progresstracker.progresstracker.service.AchievementService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class AchievementSeeder implements CommandLineRunner {

    private final AchievementService achievementService;

    public AchievementSeeder(AchievementService achievementService) {
        this.achievementService = achievementService;
    }

    @Override
    public void run(String... args) {
        achievementService.ensureDefaultAchievements();
    }
}
