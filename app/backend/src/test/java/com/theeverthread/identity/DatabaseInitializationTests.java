package com.theeverthread.identity;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.*;

/** Verifies fresh initialization, generated identifiers and manual development reset. */
@Testcontainers
class DatabaseInitializationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    @BeforeEach
    void freshSchema() throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
            initialize(statement);
        }
    }

    @Test
    void createsIndependentIdentitySequencesAndPreservesForeignKeysAndRetryUuids()
            throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
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
    void resetsPopulatedTablesInDependencyOrderAndCanInitializeAgain() throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE UNRELATED_TABLE (ID INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO UNRELATED_TABLE VALUES (1)");
            statement.execute("INSERT INTO WORKSPACE(NAME, STATUS) VALUES ('First', 'ACTIVE')");
            statement.execute(
                    "INSERT INTO ACCOUNT(EMAIL, FULL_NAME, PASSWORD_HASH, STATUS,"
                        + " DEFAULT_WORKSPACE_ID, UPDATED_AT) VALUES ('first@example.com', 'First',"
                        + " 'hash', 'ACTIVE', 1, CURRENT_TIMESTAMP)");
            statement.execute(
                    "INSERT INTO WORKSPACE_MEMBERSHIP(ACCOUNT_ID, WORKSPACE_ID, ROLE) VALUES (1, 1,"
                            + " 'OWNER')");
            statement.execute(
                    "INSERT INTO VERIFICATION_EMAIL(ACCOUNT_ID, RECIPIENT, STATUS) VALUES (1,"
                            + " 'first@example.com', 'PENDING')");
            statement.execute(
                    "INSERT INTO OAUTH_IDENTITY(ACCOUNT_ID, PROVIDER, SUBJECT_ID) VALUES (1,"
                            + " 'linkedin', 'subject')");
            statement.execute(
                    "INSERT INTO KNOWLEDGE_TICKET(AUTHOR_ID, WORKSPACE_ID, AUTHOR_NAME,"
                            + " SUBMISSION_ID, CONTENT, CREATED_AT, CLASSIFICATION_STATUS,"
                            + " NEXT_ATTEMPT_AT) VALUES (1, 1, 'First', gen_random_uuid(), '{}',"
                            + " CURRENT_TIMESTAMP, 'PENDING', CURRENT_TIMESTAMP)");
            statement.execute(
                    "INSERT INTO TEAM_INVITATION(WORKSPACE_ID, EMAIL, ADMINISTRATOR_NAME,"
                            + " ADMINISTRATOR_ID, STATUS, EXPIRES_AT, ATTEMPTED_AT) VALUES (1,"
                            + " 'next@example.com', 'First', 1, 'SENDING', CURRENT_TIMESTAMP,"
                            + " CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE DATABASECHANGELOG (ID TEXT)");
            statement.execute("CREATE TABLE DATABASECHANGELOGLOCK (ID INTEGER)");
            apply(statement, "reset-development.sql");
            assertThat(scalar(statement, "SELECT COUNT(*) FROM UNRELATED_TABLE")).isEqualTo("1");
            assertThat(
                            scalar(
                                    statement,
                                    "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                            + " table_schema='public'"))
                    .isEqualTo("1");
            initialize(statement);
            assertThat(
                            scalar(
                                    statement,
                                    "INSERT INTO WORKSPACE(NAME, STATUS) VALUES ('Recreated',"
                                            + " 'ACTIVE') RETURNING ID"))
                    .isEqualTo("1");
            assertThat(
                            scalar(
                                    statement,
                                    "SELECT COUNT(*) FROM pg_constraint WHERE"
                                            + " conrelid='workspace_membership'::regclass AND"
                                            + " contype='f'"))
                    .isEqualTo("2");
            apply(statement, "reset-development.sql");
            apply(statement, "reset-development.sql");
        }
    }

    @Test
    void rollsBackResetWhenAnUnrelatedTableReferencesApplicationData() throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO WORKSPACE(NAME, STATUS) VALUES ('Keep me', 'ACTIVE')");
            statement.execute(
                    "CREATE TABLE EXTERNAL_REFERENCE (WORKSPACE_ID BIGINT REFERENCES"
                            + " WORKSPACE(ID))");
            statement.execute("INSERT INTO EXTERNAL_REFERENCE VALUES (1)");
            assertThatThrownBy(() -> apply(statement, "reset-development.sql"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("depend");
            statement.execute("ROLLBACK");
            assertThat(scalar(statement, "SELECT NAME FROM WORKSPACE WHERE ID=1"))
                    .isEqualTo("Keep me");
            assertThat(scalar(statement, "SELECT WORKSPACE_ID FROM EXTERNAL_REFERENCE"))
                    .isEqualTo("1");
            assertThat(
                            scalar(
                                    statement,
                                    "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                            + " table_schema='public' AND"
                                            + " table_name='knowledge_ticket'"))
                    .isEqualTo("1");
        }
    }

    @Test
    void catalogMigrationPreservesExistingAttributesAndClassifierVersions() throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA catalog_upgrade; SET search_path TO catalog_upgrade");
            try {
                statement.execute(
                        "CREATE TABLE KNOWLEDGE_TICKET (ATTRIBUTES TEXT, CLASSIFICATION_VERSION"
                            + " VARCHAR(40))");
                statement.execute(
                        "INSERT INTO KNOWLEDGE_TICKET VALUES ('[\"area:backend\"]',"
                            + " 'life-work-v2')");
                apply(statement, "knowledge/20261027-001-attribute-catalog.sql");
                assertThat(scalar(statement, "SELECT ATTRIBUTES FROM KNOWLEDGE_TICKET"))
                        .isEqualTo("[\"area:backend\"]");
                assertThat(scalar(statement, "SELECT CLASSIFICATION_VERSION FROM KNOWLEDGE_TICKET"))
                        .isEqualTo("life-work-v2");
                assertThat(
                                scalar(
                                        statement,
                                        "SELECT COUNT(*) FROM KNOWLEDGE_ATTRIBUTE WHERE ACTIVE"))
                        .isEqualTo("40");
                assertThat(
                                scalar(
                                        statement,
                                        "SELECT DISPLAY_LABEL FROM KNOWLEDGE_ATTRIBUTE WHERE"
                                            + " ID='area:family'"))
                        .isEqualTo("Family");
            } finally {
                statement.execute("SET search_path TO public; DROP SCHEMA catalog_upgrade CASCADE");
            }
        }
    }

    private void initialize(Statement statement) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/changelog-master.yaml")) {
            var changelog = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (var line :
                    changelog.lines().filter(value -> value.contains("file: db/")).toList()) {
                apply(statement, line.substring(line.indexOf("file: db/") + 9).trim());
            }
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
