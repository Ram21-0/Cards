package com.cards.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Guards the environment files: dev login only in local, hardened cookie in prod, no secrets in resources. */
class ConfigFilesTest {

    private static Properties load(String path) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(path));
        return yaml.getObject();
    }

    @Test
    void devLoginIsOffByDefaultAndOnlyEnabledLocally() {
        assertEquals("false", load("application.yml").getProperty("app.dev-login.enabled"));
        assertEquals("true", load("local/application.yml").getProperty("app.dev-login.enabled"));
        assertNull(load("prod/application.yml").getProperty("app.dev-login.enabled"), "prod must not even mention dev login");
    }

    @Test
    void productionCookieIsSecureAndHostPrefixed() {
        Properties prod = load("prod/application.yml");
        assertEquals("true", prod.getProperty("app.session.cookie-secure"));
        assertEquals("__Host-session", prod.getProperty("app.session.cookie-name"));
    }

    @Test
    void localCookieWorksOverPlainHttp() {
        Properties base = load("application.yml");
        assertEquals("false", base.getProperty("app.session.cookie-secure"));
        assertEquals("session", base.getProperty("app.session.cookie-name"));
    }

    @Test
    void theGoogleClientIdIsReadFromTheEnvironmentNeverHardcoded() {
        assertEquals("${GOOGLE_CLIENT_ID:}", load("application.yml").getProperty("app.google-client-id"));
    }
}
