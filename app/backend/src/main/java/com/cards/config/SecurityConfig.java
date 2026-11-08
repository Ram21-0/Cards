package com.cards.config;

import com.cards.service.service.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Stateless, cookie/bearer session security. Needs a signed-in player: /api/me, creating a game, joining a game.
 * Everything else (decks, reading a game, the sign-in endpoints themselves) is public.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionService sessions, SessionCookies cookies) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)           // replaced by SameSite=Lax + the X-Requested-With rule (SessionAuthFilter)
                .cors(Customizer.withDefaults())                 // uses the CorsConfigurationSource bean below
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)         // we have our own POST /api/auth/logout
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> json(res, 401, "Sign in required"))
                        .accessDeniedHandler((req, res, ex) -> json(res, 403, "Forbidden")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/games", "/api/games/*/join").authenticated()   // reading a game (join-link preview) stays public
                        .anyRequest().permitAll())
                .addFilterBefore(new SessionAuthFilter(sessions, cookies), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** CORS with credentials needs the EXACT origin (never "*"). Empty list = same-origin only, no CORS headers. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!origins.isEmpty()) {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOrigins(origins);
            cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Requested-With"));
            cors.setAllowCredentials(true);
            cors.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", cors);
        }
        return source;
    }

    private static void json(jakarta.servlet.http.HttpServletResponse res, int status, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
