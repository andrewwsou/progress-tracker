package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.config.OpenApiConfig;
import com.progresstracker.progresstracker.dto.AuthResponse;
import com.progresstracker.progresstracker.dto.LoginRequest;
import com.progresstracker.progresstracker.dto.RegisterRequest;
import com.progresstracker.progresstracker.dto.validation.TimeZoneIdValidator;
import com.progresstracker.progresstracker.events.PostgresEventListener;
import com.progresstracker.progresstracker.events.UserEventStreams;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.UserRepository;
import com.progresstracker.progresstracker.security.JwtService;
import com.progresstracker.progresstracker.security.LoginThrottle;
import com.progresstracker.progresstracker.service.TimeZoneCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
@SecurityRequirements // these endpoints are public: no bearer token needed
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginThrottle loginThrottle;
    private final TimeZoneCatalog timeZoneCatalog;
    private final UserEventStreams eventStreams;
    private final JdbcTemplate jdbc;

    // Checked against when the email is unknown, so that answer takes as long as a wrong password
    // and the timing does not tell which emails have accounts. Same encoder, so the same cost.
    private final String unknownUserHash;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          LoginThrottle loginThrottle,
                          TimeZoneCatalog timeZoneCatalog,
                          UserEventStreams eventStreams,
                          JdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginThrottle = loginThrottle;
        this.timeZoneCatalog = timeZoneCatalog;
        this.eventStreams = eventStreams;
        this.jdbc = jdbc;
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/register")
    @ApiResponse(responseCode = "200", description = "Registered; returns a bearer token")
    @ApiResponse(responseCode = "409", description = "Email already used",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already used");
        }

        String hash = passwordEncoder.encode(request.password());
        User user = new User(request.email(), hash);
        String timeZone = request.timeZone();
        if (timeZone != null && TimeZoneIdValidator.isRegionId(timeZone) && timeZoneCatalog.knows(timeZone)) {
            user.setTimeZone(timeZone);
        }
        userRepository.save(user);

        return new AuthResponse(jwtService.generateToken(user.getEmail(), user.getTokenVersion()));
    }

    @PostMapping("/login")
    @ApiResponse(responseCode = "200", description = "Logged in; returns a bearer token")
    @ApiResponse(responseCode = "401", description = "Invalid credentials",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many failed sign-ins for this email or from this address",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait before trying again",
                    schema = @Schema(type = "integer")),
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        // Before the password is checked, so a guess that happens to be right is refused too, and
        // guesses sent all at once are counted before any of them is checked. Unless this sign-in
        // succeeds, the attempt stays counted as a failure.
        loginThrottle.tryAcquire(request.email(), ip);

        User user = userRepository.findByEmail(request.email()).orElse(null);
        boolean matches = passwordEncoder.matches(request.password(),
                user != null ? user.getPasswordHash() : unknownUserHash);
        if (user == null || !matches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        loginThrottle.recordSuccess(request.email(), ip);
        return new AuthResponse(jwtService.generateToken(user.getEmail(), user.getTokenVersion()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Sign out everywhere",
            description = "Ends every session the caller has, on every device: every token issued to them before "
                    + "this call stops working. Sign in again for a new one.")
    @ApiResponse(responseCode = "204", description = "Signed out")
    public void logout(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        userRepository.incrementTokenVersion(user.getId()); // commits on its own
        // An open event stream was authorized when it opened and is not checked again, so close the
        // user's streams: this instance's now, and every other instance's when the notification
        // reaches its listener. A browser that reconnects with an old token is then refused.
        eventStreams.closeAll(user.getId());
        jdbc.query("select pg_notify(?, ?)", rs -> null, PostgresEventListener.CHANNEL,
                "{\"type\":\"" + PostgresEventListener.SIGNED_OUT + "\",\"userId\":" + user.getId() + "}");
    }
}
