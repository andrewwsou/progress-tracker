package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.dto.ProfileRequest;
import com.progresstracker.progresstracker.dto.ProfileResponse;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The signed-in user's own account. */
@RestController
@RequestMapping("/api/me")
@Tag(name = "Profile")
public class ProfileController {

    private final UserRepository userRepository;

    public ProfileController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    public ProfileResponse getProfile(Authentication authentication) {
        return ProfileResponse.from(requireUser(authentication));
    }

    /** The browser sends its time zone here when it differs from the stored one (a new device, travel). */
    @PutMapping
    @Transactional
    public ProfileResponse updateProfile(@Valid @RequestBody ProfileRequest request, Authentication authentication) {
        User user = userRepository.findById(requireUser(authentication).getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
        user.setTimeZone(request.timeZone());
        return ProfileResponse.from(user);
    }

    private static User requireUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return user;
    }
}
