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

    /**
     * Password hashing is deliberately slow CPU work. Requests run on virtual threads, which share
     * one carrier thread per core, so a burst of logins could occupy every core and stall chat
     * traffic. Capping concurrent hashes at cores-1 keeps one core free for everything else.
     */
    @Bean
    PasswordHasher passwordHasher() {
        int permits = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        return new PasswordHasher(PasswordHasher.DEFAULT_ITERATIONS, permits);
    }
}
