package com.chatapp;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests run against a real Postgres, because what they check (row locks, unique
 * constraints, rollback behaviour under concurrency) is database behaviour a mock would just assume.
 *
 * <p>Skipped unless TEST_DATABASE_URL is set, so `mvn package` and the Docker build work without a
 * database. Refuses any database whose name doesn't contain "test", because it truncates tables.
 */
public final class TestDatabase {
    private TestDatabase() {}

    public static HikariDataSource fresh() {
        String url = System.getenv("TEST_DATABASE_URL");
        String dbName = url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
        if (!dbName.contains("test")) {
            throw new IllegalStateException("TEST_DATABASE_URL must point at a database whose name contains 'test', got: " + dbName);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USER", "chat"));
        config.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "chat-local-only"));
        config.setMaximumPoolSize(20);
        HikariDataSource ds = new HikariDataSource(config);

        Flyway.configure().dataSource(ds).load().migrate();
        new JdbcTemplate(ds).execute("TRUNCATE messages, conversations, sessions, users RESTART IDENTITY CASCADE");
        return ds;
    }
}
