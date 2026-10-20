package com.builtbyjuls.grid;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests backed by a shared PostgreSQL 16 container.
 * Uses the Testcontainers singleton pattern so the container persists across all test classes.
 */
public abstract class PostgreSqlTestContainerBase {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("grid_settlement_test")
                    .withUsername("test_user")
                    .withPassword("test_secret");

    static {
        postgres.start();
    }
}
