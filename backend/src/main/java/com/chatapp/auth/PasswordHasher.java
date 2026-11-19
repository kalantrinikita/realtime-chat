package com.chatapp.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Base64;
import java.util.concurrent.Semaphore;

/**
 * PBKDF2-HMAC-SHA256 password hashing using only the JDK.
 *
 * <p>Why PBKDF2 and not bcrypt/Argon2: it ships with every JDK (no native library, nothing to break
 * on Alpine/musl images), and at OWASP's recommended 600k iterations it is an accepted choice.
 * Argon2id is stronger against GPU attacks but needs BouncyCastle; the stored format below embeds
 * the algorithm and iteration count, so we can move to a stronger scheme later and rehash each user
 * transparently on their next successful login.
 *
 * <p>Stored format: {@code pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>}.
 *
 * <p>Two operational details that bite in production:
 * <ul>
 *   <li>Hashing is deliberately slow CPU work (~100-400 ms). Every request runs on a virtual thread,
 *       and virtual threads share a small pool of carrier threads (one per core). Ten concurrent
 *       logins could therefore occupy every carrier and stall all chat traffic. A semaphore caps
 *       concurrent hashes below the core count, so login bursts queue instead of starving
 *       the rest of the server.</li>
 *   <li>Passwords are Unicode-normalised (NFKC) before hashing. The same visible password typed on
 *       macOS and on Windows can arrive as different code point sequences ("é" precomposed vs. "e"
 *       plus a combining accent); without normalisation the user would be locked out on the other
 *       device.</li>
 * </ul>
 */
public final class PasswordHasher {
    public static final int DEFAULT_ITERATIONS = 600_000;

    private static final String JCA_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String FORMAT_ID = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final int iterations;
    private final Semaphore permits;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher(int iterations, int maxConcurrentHashes) {
        if (iterations < 1) throw new IllegalArgumentException("iterations must be >= 1");
        this.iterations = iterations;
        this.permits = new Semaphore(Math.max(1, maxConcurrentHashes), true);
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return FORMAT_ID + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(derived);
    }

    /** Constant-time comparison; returns false (never throws) for malformed stored values. */
    public boolean verify(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !FORMAT_ID.equals(parts[0])) return false;
        try {
            int storedIterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, salt, storedIterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iters) {
        char[] chars = Normalizer.normalize(password, Normalizer.Form.NFKC).toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iters, KEY_BITS);
        permits.acquireUninterruptibly();
        try {
            return SecretKeyFactory.getInstance(JCA_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable in this JDK", e);
        } finally {
            permits.release();
            spec.clearPassword();
            java.util.Arrays.fill(chars, '\0');
        }
    }
}
