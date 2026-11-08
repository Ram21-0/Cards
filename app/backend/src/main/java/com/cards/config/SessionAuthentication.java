package com.cards.config;

import com.cards.common.Player;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/** A request authenticated by a valid session token. The principal is the signed-in {@link Player}. */
public class SessionAuthentication extends AbstractAuthenticationToken {

    private final Player player;

    public SessionAuthentication(Player player) {
        super(List.of());
        this.player = player;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return player;
    }

    @Override
    public Object getCredentials() {
        return null;                                   // the token is never kept around
    }
}
