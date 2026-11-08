package com.cards.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the connection pool for Neon (or any Postgres) from a single DATABASE_URL.
 *
 * Neon gives you:   postgresql://user:password@ep-xxx.region.aws.neon.tech/dbname?sslmode=require
 * JDBC needs:       jdbc:postgresql://host:5432/dbname?sslmode=require   + user + password separately
 * so we convert. A ready-made "jdbc:" URL is also accepted (then set DB_USER / DB_PASSWORD).
 *
 * Defining this DataSource bean makes Spring Boot skip its own auto-configured one;
 * Liquibase and JdbcTemplate pick this one up automatically.
 */
@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(
            @Value("${DATABASE_URL:}") String databaseUrl,
            @Value("${DB_USER:}") String user,
            @Value("${DB_PASSWORD:}") String password) {

        if (databaseUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is not set. The app is running in '" + System.getProperty("user.dir")
                    + "' and found no .env.<APP_ENV> file (APP_ENV defaults to 'local') there, in 'app/backend', or next to the compiled classes. "
                    + "Create app/backend/.env.local with DATABASE_URL=..., or set DATABASE_URL under Environment variables.");
        }

        HikariConfig cfg = new HikariConfig();

        if (databaseUrl.startsWith("jdbc:")) {
            cfg.setJdbcUrl(databaseUrl);
            cfg.setUsername(user);
            cfg.setPassword(password);
        } else {
            ParsedUrl p = ParsedUrl.parse(databaseUrl);
            cfg.setJdbcUrl(p.jdbcUrl());
            cfg.setUsername(p.user());
            cfg.setPassword(p.password());
        }

        cfg.setPoolName("cards-pool");
        // Neon free tier: small pool, and let idle connections drop so the compute can auto-suspend.
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(10 * 60_000);
        // A suspended Neon database takes a second or two to wake up on the first query.
        cfg.setConnectionTimeout(30_000);
        return new HikariDataSource(cfg);
    }

    /** postgresql://user:pass@host[:port]/db[?params]  ->  pieces JDBC understands. */
    record ParsedUrl(String jdbcUrl, String user, String password) {

        static ParsedUrl parse(String url) {
            URI uri = URI.create(url);
            String info = uri.getUserInfo();                  // already percent-decoded
            if (info == null || !info.contains(":")) {
                throw new IllegalArgumentException("DATABASE_URL must look like postgresql://user:password@host/db");
            }
            int colon = info.indexOf(':');
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();

            // Neon adds channel_binding=require, which the JDBC driver does not take as a URL param. Drop it.
            List<String> params = new ArrayList<>();
            if (uri.getRawQuery() != null) {
                Arrays.stream(uri.getRawQuery().split("&"))
                        .filter(kv -> !kv.isEmpty() && !kv.startsWith("channel_binding="))
                        .forEach(params::add);
            }
            // Remote hosts (Neon) need SSL; a local Postgres normally has it off. An explicit ?sslmode= always wins.
            boolean local = List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost());
            if (params.stream().noneMatch(kv -> kv.startsWith("sslmode="))) params.add(local ? "sslmode=disable" : "sslmode=require");
            String query = String.join("&", params);

            String jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath() + "?" + query;
            return new ParsedUrl(jdbc, info.substring(0, colon), info.substring(colon + 1));
        }
    }
}
