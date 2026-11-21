-- Initial schema: users, sessions, conversations and messages.

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         TEXT        NOT NULL,
    display_name  TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_email_unique     UNIQUE (email),
    CONSTRAINT users_email_lowercase  CHECK (email = lower(email))
);

CREATE TABLE sessions (
    token_hash BYTEA       PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_user_id_idx    ON sessions (user_id);
CREATE INDEX sessions_expires_at_idx ON sessions (expires_at);

CREATE TABLE conversations (
    id              BIGSERIAL PRIMARY KEY,
    user_low        BIGINT      NOT NULL REFERENCES users (id),
    user_high       BIGINT      NOT NULL REFERENCES users (id),
    last_seq        BIGINT      NOT NULL DEFAULT 0,
    last_message_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT conversations_pair_ordered CHECK (user_low < user_high),
    CONSTRAINT conversations_pair_unique  UNIQUE (user_low, user_high)
);
-- Finds conversations by user_high (user_low is covered by the UNIQUE index above).
CREATE INDEX conversations_user_high_idx ON conversations (user_high);

CREATE TABLE messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversations (id),
    seq             BIGINT      NOT NULL,
    sender_id       BIGINT      NOT NULL REFERENCES users (id),
    client_msg_id   UUID        NOT NULL,
    body            TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT messages_conversation_seq_unique UNIQUE (conversation_id, seq),
    CONSTRAINT messages_idempotency_unique      UNIQUE (sender_id, client_msg_id),
    -- Same limit as ChatService.MAX_MESSAGE_CHARS.
    CONSTRAINT messages_body_length CHECK (char_length(body) BETWEEN 1 AND 4000)
);
