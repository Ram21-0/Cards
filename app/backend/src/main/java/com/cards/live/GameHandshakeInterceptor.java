package com.cards.live;

import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.config.SessionAuthentication;
import com.cards.service.service.GameDetails;
import com.cards.service.service.GameService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Locale;
import java.util.Map;

/**
 * Decides who may open a game's WebSocket: a signed-in player (the same session cookie as the REST API, or an
 * {@code Authorization: Bearer} header — both are resolved by the security filter before this runs) who sits in that game.
 * The Origin header is checked by the WebSocket configuration against the configured frontend origins.
 */
@Component
public class GameHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_CODE = "gameCode";
    static final String ATTR_PLAYER = "player";
    private static final String PREFIX = "/ws/games/";

    private final GameService games;

    public GameHandshakeInterceptor(GameService games) {
        this.games = games;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof SessionAuthentication) || !(authentication.getPrincipal() instanceof Player player)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        String path = request.getURI().getPath();
        if (!path.startsWith(PREFIX) || path.length() == PREFIX.length()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        String code = path.substring(PREFIX.length()).replaceAll("/+$", "").toUpperCase(Locale.ROOT);

        GameDetails details;
        try {
            details = games.get(code);
        } catch (NotFoundException e) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        boolean seated = details.seats().stream().anyMatch(s -> player.id().equals(s.playerId()));
        if (!seated) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(ATTR_CODE, details.game().joinCode());
        attributes.put(ATTR_PLAYER, player);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
        // nothing to do
    }
}
