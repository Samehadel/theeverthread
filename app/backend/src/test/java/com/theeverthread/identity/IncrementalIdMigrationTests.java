package com.theeverthread.identity;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;

/** Verifies the empty-database transition and refusal to rewrite existing identifiers. */
@Testcontainers
class IncrementalIdMigrationTests {
    private static final String MIGRATION = "foundation/20261026-001-incremental-entity-ids.sql";

    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    @BeforeEach
    void historicalSchema() throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
            try (var stream = getClass().getResourceAsStream("/db/changelog-master.yaml")) {
                var changelog = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                for (var line :
                        changelog.lines().filter(value -> value.contains("file: db/")).toList()) {
                    var path = line.substring(line.indexOf("file: db/") + 9).trim();
                    if (!path.equals(MIGRATION)) apply(statement, path);
                }
            }
        }
    }

    @Test
    void createsIndependentIdentitySequencesAndPreservesForeignKeysAndRetryUuids()
            throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            apply(statement, MIGRATION);
            connection.commit();
            connection.setAutoCommit(true);
            for (String table :
                    new String[] {
                        "workspace",
                        "account",
                        "team_invitation",
                        "workspace_membership",
                        "oauth_identity",
                        "knowledge_ticket",
                        "refresh_session"
                    }) {
                try (var result =
                        statement.executeQuery(
                                "SELECT data_type, is_identity FROM information_schema.columns"
                                        + " WHERE table_schema = 'public' AND table_name = '"
                                        + table
                                        + "' AND column_name = 'id'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("bigint");
                    assertThat(result.getString(2)).isEqualTo("YES");
                }
            }
            assertThat(
                            scalar(
                                    statement,
                                    "INSERT INTO WORKSPACE(NAME, STATUS) VALUES ('First', 'ACTIVE')"
                                            + " RETURNING ID"))
                    .isEqualTo("1");
            assertThat(
                            scalar(
                                    statement,
                                    "INSERT INTO WORKSPACE(NAME, STATUS) VALUES ('Second',"
                                            + " 'ACTIVE') RETURNING ID"))
                    .isEqualTo("2");
            assertThat(
                            scalar(
                                    statement,
                                    "INSERT INTO ACCOUNT(EMAIL, FULL_NAME, PASSWORD_HASH, STATUS,"
                                            + " DEFAULT_WORKSPACE_ID, UPDATED_AT) VALUES"
                                            + " ('first@example.com', 'First', 'hash', 'ACTIVE', 1,"
                                            + " CURRENT_TIMESTAMP) RETURNING ID"))
                    .isEqualTo("1");
            statement.execute(
                    "INSERT INTO WORKSPACE_MEMBERSHIP(ACCOUNT_ID, WORKSPACE_ID, ROLE) VALUES (1, 1,"
                            + " 'OWNER')");
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            "INSERT INTO WORKSPACE_MEMBERSHIP(ACCOUNT_ID,"
                                                + " WORKSPACE_ID, ROLE) VALUES (1, 999, 'MEMBER')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("foreign key");
            statement.execute(
                    "INSERT INTO VERIFICATION_EMAIL(ACCOUNT_ID, RECIPIENT, STATUS) VALUES (1,"
                            + " 'first@example.com', 'PENDING')");
            assertThat(
                            scalar(
                                    statement,
                                    "SELECT data_type FROM information_schema.columns WHERE"
                                            + " table_name='verification_email' AND"
                                            + " column_name='account_id'"))
                    .isEqualTo("bigint");
            for (String[] column :
                    new String[][] {
                        {"knowledge_ticket", "submission_id"},
                        {"knowledge_ticket", "lease_token"},
                        {"verification_email", "attempt_id"}
                    }) {
                assertThat(
                                scalar(
                                        statement,
                                        "SELECT data_type FROM information_schema.columns WHERE"
                                                + " table_name='"
                                                + column[0]
                                                + "' AND column_name='"
                                                + column[1]
                                                + "'"))
                        .isEqualTo("uuid");
            }
        }
    }

    @Test
    void refusesExistingDataWithoutDeletingOrChangingIt() throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
            var id = UUID.randomUUID();
            statement.execute(
                    "INSERT INTO WORKSPACE(ID, NAME, STATUS) VALUES ('"
                            + id
                            + "', 'Keep me', 'ACTIVE')");
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> apply(statement, MIGRATION))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("require empty application tables");
            connection.rollback();
            assertThat(scalar(statement, "SELECT ID FROM WORKSPACE")).isEqualTo(id.toString());
            assertThat(
                            scalar(
                                    statement,
                                    "SELECT data_type FROM information_schema.columns WHERE"
                                            + " table_name='workspace' AND column_name='id'"))
                    .isEqualTo("uuid");
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private void apply(Statement statement, String path) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/" + path)) {
            statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String scalar(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
