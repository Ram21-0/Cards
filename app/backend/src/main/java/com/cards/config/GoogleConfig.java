package com.cards.config;

import com.cards.service.service.GoogleApiTokenVerifier;
import com.cards.service.service.GoogleTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GoogleConfig {

    /** GOOGLE_CLIENT_ID comes from .env.<APP_ENV> or the environment. Empty = sign-in answers 503 "not configured". */
    @Bean
    public GoogleTokenVerifier googleTokenVerifier(@Value("${app.google-client-id:}") String clientId) {
        return new GoogleApiTokenVerifier(clientId, new NetHttpTransport(), GsonFactory.getDefaultInstance());
    }
}
