package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.model.UserAchievement;
import com.progresstracker.progresstracker.service.AchievementService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/achievements")
@CrossOrigin(origins = "http://localhost:5173")
public class AchievementController {

    private final AchievementService achievementService;

    public AchievementController(AchievementService achievementService) {
        this.achievementService = achievementService;
    }

    private User requireUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return user;
    }

    @GetMapping
    public List<UserAchievement> myAchievements(Authentication authentication) {
        User user = requireUser(authentication);
        return achievementService.getUnlockedFor(user);
    }
}
