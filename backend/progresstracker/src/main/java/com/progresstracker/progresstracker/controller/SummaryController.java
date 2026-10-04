package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.dto.WeeklySummaryResponse;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.model.WeeklySummary;
import com.progresstracker.progresstracker.repository.WeeklySummaryRepository;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/summaries")
@CrossOrigin(origins = "http://localhost:5173")
@Tag(name = "Summaries")
public class SummaryController {

    private final WeeklySummaryRepository weeklySummaryRepository;

    public SummaryController(WeeklySummaryRepository weeklySummaryRepository) {
        this.weeklySummaryRepository = weeklySummaryRepository;
    }

    @GetMapping("/latest")
    @ApiResponse(responseCode = "200", description = "The caller's most recent finished weekly summary")
    @ApiResponse(responseCode = "204", description = "No weekly summary has been written for the caller yet",
            content = @Content)
    public ResponseEntity<WeeklySummaryResponse> latest(Authentication authentication) {
        User user = requireUser(authentication);
        return weeklySummaryRepository
                .findFirstByUserIdAndStatusOrderByWeekStartDesc(user.getId(), WeeklySummary.Status.READY)
                .map(summary -> ResponseEntity.ok(WeeklySummaryResponse.from(summary)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static User requireUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return user;
    }
}
