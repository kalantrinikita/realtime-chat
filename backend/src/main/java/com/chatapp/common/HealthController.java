package com.chatapp.common;

import com.chatapp.realtime.LiveSessionRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Health check for Docker and the deploy workflow. Also checks the database is reachable. */
@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final LiveSessionRegistry liveSessions;

    public HealthController(JdbcTemplate jdbc, LiveSessionRegistry liveSessions) {
        this.jdbc = jdbc;
        this.liveSessions = liveSessions;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "ok", "liveSockets", liveSessions.count());
    }
}
