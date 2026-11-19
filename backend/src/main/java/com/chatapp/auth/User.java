package com.chatapp.auth;

/** Public view of an account. Never carries the password hash. */
public record User(long id, String email, String displayName) {}
