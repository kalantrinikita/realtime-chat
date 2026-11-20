package com.chatapp;

import com.chatapp.auth.PasswordHasher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }

    /** Limits how many passwords are hashed at once, so logins can't use every CPU core. */
    @Bean
    PasswordHasher passwordHasher() {
        int permits = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        return new PasswordHasher(PasswordHasher.DEFAULT_ITERATIONS, permits);
    }
}
