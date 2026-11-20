package com.chatapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** App settings from the {@code chat.*} section of application.yml. */
@ConfigurationProperties(prefix = "chat")
public record AppProperties(
        boolean cookieSecure,
        Duration sessionTtl,
        String inviteCode,
        List<String> allowedOrigins) {

    public AppProperties {
        sessionTtl = sessionTtl == null ? Duration.ofDays(30) : sessionTtl;
        inviteCode = inviteCode == null ? "" : inviteCode.trim();
        allowedOrigins = allowedOrigins == null ? List.of()
                : allowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Session cookie name. The {@code __Host-} prefix only works over HTTPS. */
    public String cookieName() {
        return cookieSecure ? "__Host-sid" : "sid";
    }

    public boolean inviteRequired() {
        return !inviteCode.isEmpty();
    }
}
