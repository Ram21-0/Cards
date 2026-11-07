package com.cards.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** Runs once at startup so you can see in the log whether the Neon connection works. Safe to delete later. */
@Component
public class ConnectionCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConnectionCheck.class);
    private final DataSource dataSource;

    public ConnectionCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select current_database(), version()")) {
            rs.next();
            log.info("Database connected: db={}, {}", rs.getString(1), rs.getString(2));
        }
    }
}
