package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.dto.LoginRequest;
import com.progresstracker.progresstracker.dto.RegisterRequest;
import com.progresstracker.progresstracker.events.UserEventStreams;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.UserRepository;
import com.progresstracker.progresstracker.security.JwtService;
import com.progresstracker.progresstracker.security.LoginThrottle;
import com.progresstracker.progresstracker.service.TimeZoneCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private TimeZoneCatalog timeZoneCatalog;

    @Mock
    private UserEventStreams eventStreams;

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private HttpServletRequest httpRequest;

    private AuthController controller;

    @BeforeEach
    void createController() {
        when(passwordEncoder.encode(anyString())).thenReturn("hash-of-nothing");
        controller = new AuthController(userRepository, passwordEncoder, jwtService,
                new LoginThrottle(5, 100, 50, Duration.ofMinutes(15), Clock.systemUTC()),
                timeZoneCatalog, eventStreams, jdbc);
    }

    @Test
    void anUnknownEmailStillHasAPasswordCheckedSoItTakesAsLongAsAWrongPassword() {
        when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.7");
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.login(new LoginRequest("nobody@example.com", "a-guess"), httpRequest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid credentials");
        verify(passwordEncoder).matches("a-guess", "hash-of-nothing");
    }

    @Test
    void aZoneThisDatabaseDoesNotKnowLeavesANewAccountOnUtc() {
        // Java knows it, PostgreSQL (older time zone data) does not.
        when(timeZoneCatalog.knows("America/Los_Angeles")).thenReturn(false);
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        controller.register(new RegisterRequest("new@example.com", "correct-horse-battery-staple", "America/Los_Angeles"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getTimeZone()).isEqualTo("UTC");
    }

    @Test
    void signingOutClosesTheUsersStreamsHereAndTellsTheOtherInstancesAfterTheTokensAreRevoked() {
        User user = new User("me@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", 42L);

        controller.logout(new UsernamePasswordAuthenticationToken(user, null, List.of()));

        InOrder order = inOrder(userRepository, eventStreams, jdbc);
        order.verify(userRepository).incrementTokenVersion(42L);
        order.verify(eventStreams).closeAll(42L);
        order.verify(jdbc).query(eq("select pg_notify(?, ?)"), any(ResultSetExtractor.class),
                eq("habit_events"), eq("{\"type\":\"signedOut\",\"userId\":42}"));
    }
}
