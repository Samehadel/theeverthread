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
                        + " SUBMISSION_ID, CONTENT, SUBMISSION_CONTENT, CREATED_AT,"
                        + " CLASSIFICATION_STATUS, NEXT_ATTEMPT_AT) VALUES (1, 1, 'First',"
                        + " gen_random_uuid(), '{}', '{}', CURRENT_TIMESTAMP, 'PENDING',"
                        + " CURRENT_TIMESTAMP)");
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

    @Test
    void ticketEditingMigrationPreservesOriginalPublicationAndExistingContent() throws Exception {
        try (var connection = connection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA editing_upgrade; SET search_path TO editing_upgrade");
            try {
                statement.execute("CREATE TABLE KNOWLEDGE_TICKET (CONTENT TEXT NOT NULL)");
                statement.execute("INSERT INTO KNOWLEDGE_TICKET VALUES ('original publication')");
                apply(statement, "knowledge/20261117-001-ticket-editing.sql");
                assertThat(scalar(statement, "SELECT VERSION FROM KNOWLEDGE_TICKET"))
                        .isEqualTo("0");
                assertThat(scalar(statement, "SELECT SUBMISSION_CONTENT FROM KNOWLEDGE_TICKET"))
                        .isEqualTo("original publication");
                statement.execute("UPDATE KNOWLEDGE_TICKET SET CONTENT='edited publication'");
                assertThat(scalar(statement, "SELECT SUBMISSION_CONTENT FROM KNOWLEDGE_TICKET"))
                        .isEqualTo("original publication");
            } finally {
                statement.execute("SET search_path TO public; DROP SCHEMA editing_upgrade CASCADE");
            }
        }
    }

    @Test
    void outboxMigrationPreservesUsableLinksAndDoesNotReplayAmbiguousLegacySubmissions()
            throws Exception {
        String migration = "identity/20261121-001-email-outbox.sql";
        try (var connection = connection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA email_upgrade; SET search_path TO email_upgrade");
            try {
                initialize(statement, migration);
                statement.execute(
                        """
                        INSERT INTO WORKSPACE (NAME, STATUS) VALUES ('Old workspace', 'ACTIVE');
                        INSERT INTO ACCOUNT (EMAIL, FULL_NAME, PASSWORD_HASH, STATUS, UPDATED_AT)
                        VALUES ('sent@example.com', 'Sent', 'hash', 'PENDING', CURRENT_TIMESTAMP),
                               ('inflight@example.com', 'In flight', 'hash', 'PENDING', CURRENT_TIMESTAMP),
                               ('unsent@example.com', 'Unsent', 'hash', 'PENDING', CURRENT_TIMESTAMP);
                        INSERT INTO VERIFICATION_EMAIL
                            (ACCOUNT_ID, RECIPIENT, STATUS, TOKEN_HASH, TOKEN_EXPIRES_AT,
                             LAST_SENT_AT, SMTP_MESSAGE_ID, FAILURE_CODE, NEXT_ATTEMPT_AT)
                        VALUES (1, 'sent@example.com', 'SMTP_ACCEPTED', repeat('1', 64),
                                '2030-01-02T00:00Z', '2030-01-01T00:00Z', 'old-smtp-id', NULL, NULL),
                               (2, 'inflight@example.com', 'SENDING', NULL, NULL, NULL, NULL, NULL, NULL),
                               (3, 'unsent@example.com', 'FAILED', NULL, NULL, NULL, NULL,
                                'SMTP_AUTHENTICATION_FAILED', CURRENT_TIMESTAMP);
                        INSERT INTO TEAM_INVITATION
                            (WORKSPACE_ID, EMAIL, ADMINISTRATOR_NAME, ADMINISTRATOR_ID,
                             STATUS, TOKEN_HASH, EXPIRES_AT, ATTEMPTED_AT, SENT_AT,
                             REPLACEMENT_TOKEN_HASH, REPLACEMENT_EXPIRES_AT, REPLACEMENT_ATTEMPTED_AT)
                        VALUES (1, 'member@example.com', 'Owner', 1, 'SMTP_ACCEPTED', repeat('2', 64),
                                '2030-01-08T00:00Z', '2030-01-01T00:00Z', '2030-01-01T00:00Z',
                                repeat('3', 64), '2030-01-10T00:00Z', '2030-01-03T00:00Z');
                        """);
                apply(statement, migration);
                assertThat(
                                scalar(
                                        statement,
                                        "SELECT COUNT(*) FROM EMAIL_OUTBOX WHERE STATUS='UNKNOWN'"))
                        .isEqualTo("3");
                assertThat(
                                scalar(
                                        statement,
                                        """
                                        SELECT COUNT(*) FROM VERIFICATION_EMAIL WHERE ACCOUNT_ID = 1
                                            AND TOKEN_HASH = repeat('1', 64)
                                            AND TOKEN_EXPIRES_AT = '2030-01-02T00:00Z'
                                            AND LATEST_EMAIL_ID IS NOT NULL
                                        """))
                        .isEqualTo("1");
                assertThat(
                                scalar(
                                        statement,
                                        """
                                        SELECT COUNT(*) FROM TEAM_INVITATION WHERE STATUS = 'READY'
                                            AND TOKEN_HASH = repeat('2', 64) AND EXPIRES_AT = '2030-01-08T00:00Z'
                                            AND REPLACEMENT_TOKEN_HASH IS NULL
                                        """))
                        .isEqualTo("1");
                assertThat(
                                scalar(
                                        statement,
                                        """
                                        SELECT COUNT(*) FROM VERIFICATION_EMAIL WHERE ACCOUNT_ID = 3
                                            AND LATEST_EMAIL_ID IS NULL AND NEXT_ATTEMPT_AT IS NOT NULL
                                        """))
                        .isEqualTo("1");
                assertThat(
                                scalar(
                                        statement,
                                        "SELECT COUNT(*) FROM EMAIL_DELIVERY_EVENT WHERE REASON ="
                                                + " 'old-smtp-id'"))
                        .isEqualTo("1");
            } finally {
                statement.execute("SET search_path TO public; DROP SCHEMA email_upgrade CASCADE");
            }
        }
    }

    private void initialize(Statement statement) throws Exception {
        initialize(statement, "");
    }

    private void initialize(Statement statement, String skip) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/changelog-master.yaml")) {
            var changelog = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (var line :
                    changelog.lines().filter(value -> value.contains("file: db/")).toList()) {
                String path = line.substring(line.indexOf("file: db/") + 9).trim();
                if (!path.equals(skip)) {
                    apply(statement, path);
                }
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
