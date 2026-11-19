-- Initial schema.
--
-- Design notes (the "why" behind the constraints; the app relies on every one of them):
--
-- * users.email is stored already lower-cased by the application, and the CHECK makes that an
--   invariant the database enforces, so "Alice@x.com" and "alice@x.com" can never become two
--   accounts even if a future code path forgets to normalise.
--
-- * sessions store a SHA-256 of the token, never the token itself: a leaked database backup must
--   not be a list of live logins.
--
-- * conversations store the pair in canonical order (user_low < user_high) with a UNIQUE
--   constraint. When Alice and Bob both press "start chat" at the same moment, both INSERTs target
--   the same (low, high) key, so exactly one row can exist; the loser gets ON CONFLICT DO NOTHING
--   and reads the winner's row.
--
-- * conversations.last_seq is the per-conversation message counter. Sending a message does
--   UPDATE ... SET last_seq = last_seq + 1 ... RETURNING, which row-locks the conversation until
--   commit. That makes seq allocation order identical to commit order inside a conversation, which
--   is what lets clients use "give me everything after seq N" as a gap-free cursor. Different
--   conversations never contend with each other.
--
-- * messages (conversation_id, seq) UNIQUE is both the ordering guarantee and the index that serves
--   history pages and resync queries.
--
-- * messages (sender_id, client_msg_id) UNIQUE is the idempotency key: a client that retries a send
--   after a dropped connection gets the original message back instead of a duplicate.

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
-- The UNIQUE index above already serves lookups by user_low (leading column); this one serves
-- "conversations where I am user_high".
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
    -- Backstop for the application-level limit. char_length counts Unicode code points, the same
    -- unit the server validates with (not UTF-16 units, which would count an emoji twice).
    CONSTRAINT messages_body_length CHECK (char_length(body) BETWEEN 1 AND 4000)
);
