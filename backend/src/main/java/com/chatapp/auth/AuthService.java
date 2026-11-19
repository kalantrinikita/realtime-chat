package com.chatapp.auth;

import com.chatapp.common.AppException;
import com.chatapp.config.AppProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Email + password accounts with opaque, server-side sessions.
 *
 * <p>Why server-side sessions instead of JWTs: logout must take effect immediately, including on
 * WebSocket connections that are already open (see {@code LiveSessionRegistry#closeSession}). A
 * self-contained JWT stays valid until it expires unless you add a revocation list, which is a
 * session table again.
 *
 * <p>The token travels in an HttpOnly cookie because the browser WebSocket API cannot set an
 * Authorization header on the handshake, but it does send cookies. Only SHA-256(token) is stored,
 * so a leaked database backup is not a list of live logins.
 */
@Service
public class AuthService {
    /** Deliberately loose: the real test of an address is "can it receive mail", which we don't do. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final RowMapper<User> USER = (rs, i) -> new User(rs.getLong("id"), rs.getString("email"), rs.getString("display_name"));

    public record SessionResult(User user, String token) {}
    public record AuthenticatedSession(User user, byte[] tokenHash) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordHasher hasher;
    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();
    /**
     * Hash of a random password, checked when a login names an unknown email. Without it, "no such
     * email" answers in ~1 ms and "wrong password" in ~300 ms, and that difference tells an attacker
     * which emails have accounts.
     */
    private final String dummyHash;

    public AuthService(JdbcTemplate jdbc, PlatformTransactionManager txManager, PasswordHasher hasher, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.hasher = hasher;
        this.props = props;
        this.dummyHash = hasher.hash(Base64.getEncoder().encodeToString(randomBytes(18)));
    }

    public SessionResult register(String rawEmail, String password, String rawDisplayName, String inviteCode) {
        if (props.inviteRequired() && !constantTimeEquals(props.inviteCode(), inviteCode == null ? "" : inviteCode.trim())) {
            throw AppException.forbidden("Invalid invite code");
        }
        String email = normalizeEmail(rawEmail);
        validatePassword(password);
        String displayName = normalizeDisplayName(rawDisplayName, email);
        String hash = hasher.hash(password); // outside the transaction: slow, and needs no DB
        try {
            return Objects.requireNonNull(tx.execute(status -> {
                Long id = jdbc.queryForObject(
                        "INSERT INTO users(email, display_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                        Long.class, email, displayName, hash);
                User user = new User(Objects.requireNonNull(id), email, displayName);
                return new SessionResult(user, createSession(user.id()));
            }));
        } catch (DuplicateKeyException e) {
            // Rely on the UNIQUE constraint, not "SELECT then INSERT": two sign-ups racing for the
            // same email would both pass the SELECT.
            throw AppException.conflict("An account with this email already exists");
        }
    }

    public SessionResult login(String rawEmail, String password) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        record Row(User user, String hash) {}
        List<Row> rows = jdbc.query(
                "SELECT id, email, display_name, password_hash FROM users WHERE email = ?",
                (rs, i) -> new Row(USER.mapRow(rs, i), rs.getString("password_hash")), email);
        Optional<Row> row = rows.stream().findFirst();
        boolean ok = hasher.verify(password == null ? "" : password, row.map(Row::hash).orElse(dummyHash));
        if (row.isEmpty() || !ok) {
            throw AppException.unauthorized("Incorrect email or password"); // same text for both cases
        }
        User user = row.get().user();
        return new SessionResult(user, createSession(user.id()));
    }

    /** Deletes the session and returns its hash, so the caller can close sockets opened with it. */
    public Optional<byte[]> logout(String token) {
        if (token == null || token.isEmpty()) return Optional.empty();
        byte[] tokenHash = sha256(token);
        jdbc.update("DELETE FROM sessions WHERE token_hash = ?", (Object) tokenHash);
        return Optional.of(tokenHash);
    }

    public Optional<AuthenticatedSession> authenticate(String token) {
        if (token == null || token.isEmpty() || token.length() > 128) return Optional.empty();
        byte[] tokenHash = sha256(token);
        List<User> users = jdbc.query("""
                SELECT u.id, u.email, u.display_name
                FROM sessions s JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.expires_at > now()""", USER, (Object) tokenHash);
        return users.stream().findFirst().map(u -> new AuthenticatedSession(u, tokenHash));
    }

    public Optional<User> findByEmail(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        return jdbc.query("SELECT id, email, display_name FROM users WHERE email = ?", USER, email)
                .stream().findFirst();
    }

    private String createSession(long userId) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        // Housekeeping without a scheduler: each login clears that user's expired sessions.
        jdbc.update("DELETE FROM sessions WHERE user_id = ? AND expires_at <= now()", userId);
        jdbc.update("INSERT INTO sessions(token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                sha256(token), userId, Timestamp.from(Instant.now().plus(props.sessionTtl())));
        return token;
    }

    // ---- validation ----------------------------------------------------------------------------

    /**
     * Lower-cases with {@link Locale#ROOT} on purpose: if the JVM's default locale is Turkish,
     * "I".toLowerCase() is the dotless "ı", which would silently create a different account key
     * depending on the server it runs on.
     */
    static String normalizeEmail(String raw) {
        if (raw == null) throw AppException.badRequest("Email is required");
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw AppException.badRequest("Please enter a valid email address");
        }
        return email;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8) throw AppException.badRequest("Password must be at least 8 characters");
        // Upper bound so nobody can post a 10 MB "password" to burn CPU in the hash function.
        if (password.length() > 128) throw AppException.badRequest("Password must be at most 128 characters");
    }

    private static String normalizeDisplayName(String raw, String email) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) name = email.substring(0, email.indexOf('@'));
        if (name.codePointCount(0, name.length()) > 50) throw AppException.badRequest("Display name must be at most 50 characters");
        // Control and bidi-format characters (newlines, right-to-left overrides used for spoofing,
        // NUL which Postgres rejects) have no place in a name shown to other people.
        if (name.codePoints().anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT)) {
            throw AppException.badRequest("Display name contains invalid characters");
        }
        return name;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }
}
