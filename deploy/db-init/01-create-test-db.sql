-- Runs once, when the Postgres volume is first created (docker-entrypoint-initdb.d).
-- A separate database for the integration tests, which TRUNCATE every table before each test.
-- Pointing them at the real "chat" database would wipe your local accounts and messages.
CREATE DATABASE chat_test OWNER chat;
