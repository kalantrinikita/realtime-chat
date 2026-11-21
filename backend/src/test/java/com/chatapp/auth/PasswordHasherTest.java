package com.chatapp.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    // Fewer iterations so the tests run fast.
    private final PasswordHasher hasher = new PasswordHasher(1_000, 2);

    @Test
    void verifiesCorrectPasswordAndRejectsWrongOne() {
        String stored = hasher.hash("correct horse battery staple");
        assertTrue(stored.startsWith("pbkdf2-sha256$1000$"));
        assertTrue(hasher.verify("correct horse battery staple", stored));
        assertFalse(hasher.verify("Correct horse battery staple", stored));
    }

    @Test
    void saltsMakeEqualPasswordsHashDifferently() {
        assertNotEquals(hasher.hash("same password"), hasher.hash("same password"));
    }

    @Test
    void precomposedAndDecomposedAccentsAreTheSamePassword() {
        String precomposed = "café-päss";     // é, ä as single code points (typical on Windows)
        String decomposed = "café-päss";    // e + combining acute, a + combining diaeresis (macOS)
        assertTrue(hasher.verify(decomposed, hasher.hash(precomposed)));
    }

    @Test
    void usesTheIterationCountStoredWithTheHash() {
        String oldHash = new PasswordHasher(500, 1).hash("pw-12345678");
        assertTrue(hasher.verify("pw-12345678", oldHash), "hashes from an older iteration setting stay valid");
    }

    @Test
    void malformedStoredValuesNeverVerify() {
        assertFalse(hasher.verify("x", "not-a-hash"));
        assertFalse(hasher.verify("x", "pbkdf2-sha256$abc$!!$!!"));
    }

    @Test
    void emailNormalisationIsLocaleIndependent() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals("ivan@example.com", AuthService.normalizeEmail("  IVAN@Example.COM "));
        } finally {
            java.util.Locale.setDefault(previous);
        }
        assertThrows(com.chatapp.common.AppException.class, () -> AuthService.normalizeEmail("not an email"));
    }
}
