package com.progresstracker.progresstracker.config;

import jakarta.servlet.DispatcherType;
import com.progresstracker.progresstracker.security.BearerAuthenticationEntryPoint;
import com.progresstracker.progresstracker.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final BearerAuthenticationEntryPoint authenticationEntryPoint;
    private final List<String> allowedOrigins;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          BearerAuthenticationEntryPoint authenticationEntryPoint,
                          // The only place browser origins are allowed: no controller adds its own.
                          @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:4173}")
                          List<String> allowedOrigins) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No valid token: answer 401, not Spring Security's default 403.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        // The second dispatch that finishes an asynchronous response (an event
                        // stream closing). Its request was authorized when it started; the JWT
                        // filter does not run again for it.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Signing out acts on the caller's account, so it needs a token, unlike the rest of /api/auth.
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        // Liveness probe for containers and load balancers. Exposes status only.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // The API contract and its browsable UI.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // Authorized via X-Internal-Token in AutomationController, not JWT.
                        .requestMatchers("/api/internal/**").permitAll()
                        // Errors that fall through to Boot's /error page are re-dispatched through
                        // this chain without the JWT filter, so /error must be reachable or the
                        // real status would be replaced by a 401.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // So the sign-in page can read how long a throttled sign-in has to wait.
        configuration.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
