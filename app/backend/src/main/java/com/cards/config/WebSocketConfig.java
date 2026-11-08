package com.cards.config;

import com.cards.live.GameHandshakeInterceptor;
import com.cards.live.GameWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Arrays;

/** The game WebSocket: {@code /ws/games/{joinCode}}, a raw WebSocket with JSON text messages (not STOMP). */
@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameWebSocketHandler handler;
    private final GameHandshakeInterceptor interceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(GameWebSocketHandler handler, GameHandshakeInterceptor interceptor,
                           @Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration = registry.addHandler(handler, "/ws/games/*").addInterceptors(interceptor);
        // The Origin header is checked: the configured frontend origins, plus the site's own origin (same-origin is always allowed).
        // Without any configured origin only same-origin connections are accepted.
        if (allowedOrigins.length > 0) registration.setAllowedOrigins(allowedOrigins);
    }

    /** Small limits: clients only ever send tiny ping messages. */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(4 * 1024);
        container.setMaxBinaryMessageBufferSize(1024);
        container.setMaxSessionIdleTimeout(10 * 60 * 1000L);
        return container;
    }
}
