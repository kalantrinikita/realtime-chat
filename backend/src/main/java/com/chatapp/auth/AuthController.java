package com.chatapp.auth;

import com.chatapp.config.AppProperties;
import com.chatapp.realtime.LiveSessionRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** REST endpoints for sign-up, login, logout and the current user. */

@RestController
@RequestMapping("/api")
public class AuthController {
    public record RegisterRequest(String email, String password, String displayName, String inviteCode) {}
    public record LoginRequest(String email, String password) {}

    private final AuthService auth;
    private final SessionCookies cookies;
    private final AppProperties props;
    private final LiveSessionRegistry liveSessions;
    private final JdbcTemplate jdbc;

    public AuthController(AuthService auth, SessionCookies cookies, AppProperties props,
                          LiveSessionRegistry liveSessions, JdbcTemplate jdbc) {
        this.auth = auth;
        this.cookies = cookies;
        this.props = props;
        this.liveSessions = liveSessions;
        this.jdbc = jdbc;
    }

    /** Used by the Docker healthcheck; to verify that the DB makes "can actually serve". */
    @GetMapping("/health")
    public Map<String, Object> health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "ok", "liveSockets", liveSessions.count());
    }

    /** Settings the sign-up page needs before login. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("inviteRequired", props.inviteRequired());
    }

    @PostMapping(path = "/auth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        AuthService.SessionResult result = auth.register(req.email(), req.password(), req.displayName(), req.inviteCode());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookies.create(result.token()))
                .body(Map.of("user", result.user()));
    }

    @PostMapping(path = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req) {
        AuthService.SessionResult result = auth.login(req.email(), req.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.create(result.token()))
                .body(Map.of("user", result.user()));
    }

    /** Ends the session and closes any WebSockets opened with it. */
    @PostMapping(path = "/auth/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        cookies.read(request).flatMap(auth::logout).ifPresent(liveSessions::closeSession);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestAttribute(AuthInterceptor.CURRENT_USER) User me) {
        return Map.of("user", me);
    }
}
