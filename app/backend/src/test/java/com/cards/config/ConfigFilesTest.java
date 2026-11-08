package com.cards.config;

import com.cards.common.DifficultyLevel;
import com.cards.game.CpuProfiles;
import com.cards.game.GameSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void theGameDefaultsAndCpuProfilesInApplicationYmlAreValidAndMatchTheCodeDefaults() {
        Properties p = load("application.yml");
        assertEquals("500", p.getProperty("app.game.max-rounds"));
        assertEquals("3", p.getProperty("app.game.choose-threshold"));
        assertEquals("5", p.getProperty("app.game.clash-call-seconds"));
        assertNull(p.getProperty("app.game.turn-timeout-seconds"), "no turn limit unless configured");

        CpuProfiles configured = new GameConfig().cpuProfiles(
                p.getProperty("app.game.cpu.easy.strategy"), Double.parseDouble(p.getProperty("app.game.cpu.easy.noise")),
                p.getProperty("app.game.cpu.medium.strategy"), Double.parseDouble(p.getProperty("app.game.cpu.medium.noise")),
                p.getProperty("app.game.cpu.hard.strategy"), Double.parseDouble(p.getProperty("app.game.cpu.hard.noise")));
        for (DifficultyLevel level : DifficultyLevel.values()) {
            assertEquals(CpuProfiles.defaults().profile(level), configured.profile(level), level.name());
        }
        GameSettings settings = new GameConfig().defaultGameSettings(500, 3, 5, null);
        assertEquals(GameSettings.defaults(), settings);
    }

    @Test
    void anInvalidCpuStrategyInConfigurationStopsStartupWithAClearMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GameConfig().cpuProfiles("random", 0, "clever", 0, "smart", 0));
        assertTrue(e.getMessage().contains("clever"));
        assertThrows(IllegalArgumentException.class, () -> new GameConfig().cpuProfiles("random", 0, "smart", 1.5, "smart", 0));
        assertThrows(IllegalArgumentException.class, () -> new GameConfig().defaultGameSettings(5, 3, 5, null));
    }

    @Test
    void theGoogleClientIdIsReadFromTheEnvironmentNeverHardcoded() {
        assertEquals("${GOOGLE_CLIENT_ID:}", load("application.yml").getProperty("app.google-client-id"));
    }
}
