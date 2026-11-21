package com.chatapp.chat;

import com.chatapp.common.AppException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageValidationTest {

    @Test
    void rejectsEmptyAndBlank() {
        assertThrows(AppException.class, () -> ChatService.validateBody(null));
        assertThrows(AppException.class, () -> ChatService.validateBody("   \n "));
    }

    @Test
    void countsCodePointsNotUtf16Units() {
        // An emoji counts as one character.
        assertDoesNotThrow(() -> ChatService.validateBody("😀".repeat(4000)));
        assertThrows(AppException.class, () -> ChatService.validateBody("x".repeat(4001)));
    }

    @Test
    void rejectsNulWhichPostgresCannotStore() {
        assertThrows(AppException.class, () -> ChatService.validateBody("a\u0000b"));
    }
}
