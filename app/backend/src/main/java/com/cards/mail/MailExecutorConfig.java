package com.cards.mail;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One daemon thread for outgoing mail, so sending never happens on a request thread. */
@Configuration
public class MailExecutorConfig {

    @Bean(name = "mailExecutor", destroyMethod = "shutdown")
    public ExecutorService mailExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mail");
            t.setDaemon(true);
            return t;
        });
    }
}
