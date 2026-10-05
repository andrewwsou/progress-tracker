package com.progresstracker.progresstracker.dto;

import com.progresstracker.progresstracker.dto.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(min = 1, max = 255) String email,
        // bcrypt refuses anything over 72 bytes, and a non-ASCII character is more than one byte,
        // so the byte limit is checked as well as the character count.
        @NotBlank
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        @MaxUtf8Bytes(72)
        String password,
        // Optional: the browser's time zone, so "today" is the user's day. UTC when left out or not a
        // zone the server can use: an odd browser setting must not stop anyone signing up.
        @Size(max = 64)
        String timeZone
) {
}
