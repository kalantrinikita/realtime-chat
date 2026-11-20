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
 * Hashes and checks passwords with PBKDF2-HMAC-SHA256.
 * Stored as {@code pbkdf2-sha256$<iterations>$<salt>$<hash>}.
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

    /** Checks a password against a stored hash. Returns false if the stored value is invalid. */
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
