package com.chatapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * App-specific settings ({@code chat.*} in application.yml, each overridable by env var).
 * The ones that must change for a public deployment: COOKIE_SECURE and INVITE_CODE.
 */
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

    /**
     * Browsers only accept a {@code __Host-} cookie if it is Secure, Path=/ and has no Domain.
     * That prefix stops a sibling subdomain from overwriting our session cookie, so we use it
     * whenever we run over HTTPS. Plain-HTTP localhost can't, hence the fallback name.
     */
    public String cookieName() {
        return cookieSecure ? "__Host-sid" : "sid";
    }

    public boolean inviteRequired() {
        return !inviteCode.isEmpty();
    }
}
