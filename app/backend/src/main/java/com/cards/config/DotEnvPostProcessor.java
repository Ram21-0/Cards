package com.cards.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Local development convenience: loads KEY=value lines from {@code .env.<APP_ENV>} (APP_ENV defaults to "local").
 *
 * The file is searched relative to the working directory AND relative to where the compiled classes live, so it is
 * found whatever folder the IDE starts the app from. It is added with the LOWEST precedence, so real environment
 * variables and system properties (what you set on a production host) always win.
 */
public class DotEnvPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String fileName = ".env." + env.getProperty("APP_ENV", "local");

        for (File dir : candidateDirs()) {
            File file = new File(dir, fileName);
            if (!file.isFile()) continue;
            try (InputStream in = Files.newInputStream(file.toPath())) {
                Properties props = new Properties();
                props.load(in);
                env.getPropertySources().addLast(new PropertiesPropertySource("dotenv:" + file.getAbsolutePath(), props));
                return;                                  // first match wins
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + file, e);
            }
        }
    }

    /** Working dir, working dir/app/backend, and the backend project dir derived from the classes/jar location. */
    private static Set<File> candidateDirs() {
        Set<File> dirs = new LinkedHashSet<>();
        File cwd = new File(System.getProperty("user.dir"));
        dirs.add(cwd);
        dirs.add(new File(cwd, "app/backend"));

        File home = new ApplicationHome(DotEnvPostProcessor.class).getDir();   // target/classes  or  target (jar)
        for (int i = 0; i < 3 && home != null; i++, home = home.getParentFile()) {
            dirs.add(home);                              // reaches app/backend from either layout
        }
        return dirs;
    }
}
