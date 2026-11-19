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
        // 4000 emoji are 8000 UTF-16 units but 4000 characters to a human (and to Postgres).
        assertDoesNotThrow(() -> ChatService.validateBody("😀".repeat(4000)));
        assertThrows(AppException.class, () -> ChatService.validateBody("x".repeat(4001)));
    }

    @Test
    void rejectsNulWhichPostgresCannotStore() {
        assertThrows(AppException.class, () -> ChatService.validateBody("a\u0000b"));
    }
}
