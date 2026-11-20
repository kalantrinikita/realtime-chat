package com.chatapp.common;

/** An expected error that is sent back to the client, with an HTTP status and an error code. */
public class AppException extends RuntimeException {
    private final int status;
    private final String code;
    private final boolean retryable;

    public AppException(int status, String code, String message) {
        this(status, code, message, false);
    }

    public AppException(int status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public int status() { return status; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }

    public static AppException badRequest(String message) {
        return new AppException(400, "validation_error", message);
    }

    public static AppException notFound(String message) {
        return new AppException(404, "not_found", message);
    }

    public static AppException unauthorized(String message) {
        return new AppException(401, "unauthorized", message);
    }

    public static AppException forbidden(String message) {
        return new AppException(403, "forbidden", message);
    }

    public static AppException conflict(String message) {
        return new AppException(409, "conflict", message);
    }
}
