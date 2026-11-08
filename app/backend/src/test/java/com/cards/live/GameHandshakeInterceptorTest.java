package com.cards.live;

import com.cards.common.GameStatus;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.config.SessionAuthentication;
import com.cards.service.service.GameService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.cards.live.LiveTestKit.CODE;
import static com.cards.live.LiveTestKit.details;
import static com.cards.live.LiveTestKit.human;
import static com.cards.live.LiveTestKit.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who may open a game's WebSocket: a signed-in player who sits in that game. */
class GameHandshakeInterceptorTest {

    private final GameService games = mock(GameService.class);
    private final GameHandshakeInterceptor interceptor = new GameHandshakeInterceptor(games);
    private final Player ann = player("ann"), bob = player("bob"), stranger = player("stranger");
    private final MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    private final ServletServerHttpResponse response = new ServletServerHttpResponse(servletResponse);
    private final Map<String, Object> attributes = new HashMap<>();

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private boolean handshake(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        return interceptor.beforeHandshake(new ServletServerHttpRequest(request), response, null, attributes);
    }

    private void signIn(Player p) {
        SecurityContextHolder.getContext().setAuthentication(new SessionAuthentication(p));
    }

    private void gameWithSeated() {
        when(games.get(CODE)).thenReturn(details(GameStatus.WAITING, ann, List.of(human(1, ann), human(2, bob))));
    }

    @Test
    void aSignedInPlayerWhoSitsInTheGameIsLetIn() {
        gameWithSeated();
        signIn(bob);
        assertTrue(handshake("/ws/games/" + CODE));
        assertEquals(CODE, attributes.get(GameHandshakeInterceptor.ATTR_CODE));
        assertEquals(bob, attributes.get(GameHandshakeInterceptor.ATTR_PLAYER));
    }

    @Test
    void theCodeInThePathIsCaseInsensitive() {
        gameWithSeated();
        signIn(ann);
        assertTrue(handshake("/ws/games/" + CODE.toLowerCase()));
        assertEquals(CODE, attributes.get(GameHandshakeInterceptor.ATTR_CODE));
    }

    @Test
    void anonymousVisitorsAreRefusedWith401() {
        gameWithSeated();
        assertFalse(handshake("/ws/games/" + CODE));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), servletResponse.getStatus());
        assertTrue(attributes.isEmpty());
    }

    @Test
    void aSignedInPlayerWhoIsNotInTheGameGets403() {
        gameWithSeated();
        signIn(stranger);
        assertFalse(handshake("/ws/games/" + CODE));
        assertEquals(HttpStatus.FORBIDDEN.value(), servletResponse.getStatus());
    }

    @Test
    void anUnknownGameGets404() {
        when(games.get("NOPE12")).thenThrow(new NotFoundException("Game NOPE12 not found"));
        signIn(ann);
        assertFalse(handshake("/ws/games/NOPE12"));
        assertEquals(HttpStatus.NOT_FOUND.value(), servletResponse.getStatus());
    }

    @Test
    void aPathWithoutACodeGets404() {
        signIn(ann);
        assertFalse(handshake("/ws/games/"));
        assertEquals(HttpStatus.NOT_FOUND.value(), servletResponse.getStatus());
    }

    @Test
    void anAuthenticationThatIsNotASessionIsNotAccepted() {
        gameWithSeated();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ann, null));
        assertFalse(handshake("/ws/games/" + CODE));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), servletResponse.getStatus());
    }
}
