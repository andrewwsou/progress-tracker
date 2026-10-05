package com.progresstracker.progresstracker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record LoginRequest(
        // No account has a longer one (see RegisterRequest), and a failed sign-in's email is held
        // by the login throttle for a while, so an oversized one is refused before it gets there.
        @NotBlank @Size(min = 1, max = 255) String email,
        @NotBlank String password
) {

    /** Emails are stored in lower case (see RegisterRequest), so a sign-in matches however it is typed. */
    public LoginRequest {
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }
    }
}
