package com.theeverthread.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.theeverthread.identity.api.dto.RegistrationRequest;
import com.theeverthread.identity.api.exception.IdentityException;
import com.theeverthread.identity.api.service.IdentityService;
import com.theeverthread.identity.internal.service.PendingRegistrationCleanup;
import com.theeverthread.identity.internal.service.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
@SpringBootTest(properties = "theeverthread.registration.delivery.enabled=false")
@AutoConfigureMockMvc
class RegistrationIntegrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired
    IdentityService identity;
    @Autowired com.theeverthread.identity.internal.service.EmailDeliveryWorker worker;
    @Autowired
    PendingRegistrationCleanup cleanup;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;
    @MockitoBean ResendClient mail;
    @Autowired ResendWebhook webhook;
    TestEmailDelivery fake;
    @MockitoBean
    Clock clock;
    final Instant start = Instant.parse("2026-10-04T12:00:00Z");

    record Link(Long id, String token) {}

    Link verificationLink(String email) {
        var message = fake.latest(email);
        return new Link(message.targetId(), message.token());
    }

    final String password = "Validpass!";

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE REFRESH_SESSION, VERIFICATION_EMAIL, ACCOUNT, WORKSPACE CASCADE");
        when(clock.instant()).thenReturn(start);
        fake = new TestEmailDelivery(mail, webhook, clock);
    }

    RegistrationRequest request(String email, String website) {
        return new RegistrationRequest("Ada Example", email, password);
    }

    void register(String email, String domain) {
        identity.register(request(email, domain));
        worker.runOnce();
    }

    void verify(String email) {
        var link = fake.latest(email);
        identity.verify(link.targetId(), link.token());
    }

    int activeWorkspaces() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM WORKSPACE WHERE STATUS='ACTIVE'", Integer.class);
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void registrationIsPendingAndPasswordIsHashedWithReusableRoles() throws Exception {
        var body = request("Ada@elsewhere.com", "Workspace.CO.UK");
        mvc.perform(
                        post("/api/registration")
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("ada@elsewhere.com"));
        assertThat(activeWorkspaces()).isZero();
        assertThat(count("ACCOUNT")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT STATUS FROM ACCOUNT", String.class))
                .isEqualTo("PENDING");
        assertThat(count("WORKSPACE")).isZero();
        assertThat(jdbc.queryForObject("SELECT PASSWORD_HASH FROM ACCOUNT", String.class))
                .doesNotContain(password);
        assertThat(jdbc.queryForObject("SELECT STATUS FROM VERIFICATION_EMAIL", String.class))
                .isEqualTo("PENDING");
        verifyNoInteractions(mail);
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT TOKEN_HASH FROM VERIFICATION_EMAIL", String.class))
                .doesNotContain(fake.latest("ada@elsewhere.com").token());
        mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "ada@elsewhere.com",
                                                        "password",
                                                        password))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/invitations").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void verifyCreatesMembershipAndPasswordLoginSessionLogoutWork() throws Exception {
        register("ada@example.com", "company.com");
        var link = fake.latest("ada@example.com");
        mvc.perform(
                        post("/api/registration/verify")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                verificationLink(link.recipient()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"));
        assertThat(activeWorkspaces()).isEqualTo(1);
        assertThat(identity.current("ada@example.com").accessRole()).isEqualTo("OWNER");
        assertThat(identity.current("ada@example.com").accessRole()).isEqualTo("OWNER");
        assertThat(identity.verify(link.targetId(), link.token()).status())
                .isEqualTo("ALREADY_VERIFIED");
        var old = new MockHttpSession();
        String oldId = old.getId();
        var response =
                mvc.perform(
                                post("/api/auth/login")
                                        .session(old)
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "email",
                                                                "ADA@example.com",
                                                                "password",
                                                                password))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.workspaceName").value("My workspace"))
                        .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                        .andReturn();
        var session = (MockHttpSession) response.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("ada@example.com"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicMutationsRequireCsrfAndMaskedTokenWorks() throws Exception {
        mvc.perform(
                        post("/api/registration")
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                request("ada@example.com", "company.com"))))
                .andExpect(status().isForbidden());
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var data = json.readTree(response.getResponse().getContentAsString()).path("data");
        mvc.perform(
                        post("/api/registration")
                                .session((MockHttpSession) response.getRequest().getSession(false))
                                .header(
                                        data.path("headerName").asText(),
                                        data.path("token").asText())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                request("ada@example.com", "company.com"))))
                .andExpect(status().isOk());
    }

    @Test
    void validationIsActionableAndNoPartialDataIsSaved() throws Exception {
        mvc.perform(
                        post("/api/registration")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThatThrownBy(
                        () ->
                                identity.register(
                                        new RegistrationRequest("Ada", "ada@example.com", "short")))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(
                        () ->
                                identity.register(
                                        new RegistrationRequest(" ", "ada@example.com", password)))
                .isInstanceOf(IdentityException.class);
        assertThat(count("ACCOUNT")).isZero();
        verifyNoInteractions(mail);
    }

    @Test
    void duplicatesCheckDomainAndAnyAccountMembershipNotWorkspaceName() {
        register("ada@example.com", "same.com");
        verify("ada@example.com");
        register("other@example.com", "same.com");
        verify("other@example.com");
        assertThatThrownBy(() -> register("ADA@example.com", "other.com"))
                .isInstanceOf(IdentityException.class);
        assertThat(activeWorkspaces()).isEqualTo(2);
        assertThat(identity.current("ada@example.com").workspaceId())
                .isNotEqualTo(identity.current("other@example.com").workspaceId());
    }

    @Test
    void repeatedRegistrationDoesNotChangeCredentialsOrSendAgain() {
        register("ada@example.com", "company.com");
        var original = fake.latest("ada@example.com");
        assertThatThrownBy(
                        () ->
                                identity.register(
                                        new RegistrationRequest(
                                                "Wrong",
                                                "ADA@example.com",
                                                "Another long password!")))
                .isInstanceOf(IdentityException.class);
        assertThat(count("ACCOUNT")).isEqualTo(1);
        org.mockito.Mockito.verify(mail, times(1)).submit(any(), any(byte[].class));
        assertThat(fake.latest("ada@example.com")).isEqualTo(original);
        verify("ada@example.com");
        assertThat(identity.current("ada@example.com").workspaceName()).isEqualTo("My workspace");
    }

    @Test
    void cooldownDoesNotInvalidateAndResendRotatesSameRecord() {
        register("ada@example.com", "company.com");
        var first = fake.latest("ada@example.com");
        when(clock.instant()).thenReturn(start.plusSeconds(59));
        assertThatThrownBy(() -> identity.resend("ada@example.com"))
                .isInstanceOfSatisfying(
                        IdentityException.class,
                        e -> assertThat(e.code()).isEqualTo("RESEND_COOLDOWN"));
        assertThat(fake.latest("ada@example.com")).isEqualTo(first);
        org.mockito.Mockito.verify(mail, times(1)).submit(any(), any(byte[].class));
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        identity.resend("ada@example.com");
        worker.runOnce();
        var second = fake.latest("ada@example.com");
        assertThat(second.targetId()).isEqualTo(first.targetId());
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThatThrownBy(() -> identity.verify(first.targetId(), first.token()))
                .hasMessageContaining("replaced");
        when(clock.instant()).thenReturn(start.plus(Duration.ofHours(24)).plusSeconds(59));
        verify("ada@example.com");
        assertThat(count("ACCOUNT")).isEqualTo(1);
        assertThat(activeWorkspaces()).isEqualTo(1);
    }

    @Test
    void earlyResendLeavesOriginalLinkUsable() {
        register("ada@example.com", "company.com");
        assertThatThrownBy(() -> identity.resend("ada@example.com"))
                .isInstanceOf(IdentityException.class);
        verify("ada@example.com");
        assertThat(activeWorkspaces()).isEqualTo(1);
    }

    @Test
    void exactlyTwentyFourHoursIsExpiredAndInvalidTokenCannotActivate() {
        register("ada@example.com", "company.com");
        var link = fake.latest("ada@example.com");
        assertThatThrownBy(() -> identity.verify(link.targetId(), "X".repeat(43)))
                .hasMessageContaining("invalid");
        when(clock.instant()).thenReturn(start.plus(Duration.ofHours(24)));
        assertThatThrownBy(() -> identity.verify(link.targetId(), link.token()))
                .hasMessageContaining("expired");
        assertThat(activeWorkspaces()).isZero();
        identity.resend("ada@example.com");
        worker.runOnce();
        verify("ada@example.com");
        assertThat(activeWorkspaces()).isEqualTo(1);
    }


    @Test
    void failedEmailDeliveryPersistsFailureAndPreservesPreviousTokenOnResend() {
        register("ada@example.com", "company.com");
        var first = fake.latest("ada@example.com");
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        doReturn(TestEmailDelivery.rejected()).when(mail).submit(any(), any(byte[].class));
        identity.resend("ada@example.com");
        worker.runOnce();
        assertThat(identity.deliveryStatus("ada@example.com").deliveryStatus()).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM EMAIL_OUTBOX WHERE"
                                        + " FAILURE_CODE='validation_error'",
                                Integer.class))
                .isEqualTo(1);
        identity.verify(first.targetId(), first.token());
        assertThat(activeWorkspaces()).isEqualTo(1);
    }

    @Test
    void competingVerificationsCreateExactlyOneWorkspaceAndDoNotActivateLoser() throws Exception {
        register("ada@example.com", "same.com");
        var link = fake.latest("ada@example.com");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> identity.verify(link.targetId(), link.token()));
            var second = pool.submit(() -> identity.verify(link.targetId(), link.token()));
            assertThat(
                            List.of(
                                    first.get(15, TimeUnit.SECONDS).status(),
                                    second.get(15, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder("VERIFIED", "ALREADY_VERIFIED");
        }
        assertThat(activeWorkspaces()).isEqualTo(1);
        assertThat(count("WORKSPACE_MEMBERSHIP")).isEqualTo(1);
    }

    @Test
    void simultaneousRegistrationsAndResendsDoNotDuplicateAccountsOrEmails() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a =
                    pool.submit(
                            () -> {
                                try {
                                    identity.register(request("ada@example.com", "company.com"));
                                } catch (IdentityException expected) {
                                    assertThat(expected.code()).isEqualTo("EMAIL_IN_USE");
                                }
                            });
            var b =
                    pool.submit(
                            () -> {
                                try {
                                    identity.register(request("ADA@example.com", "company.com"));
                                } catch (IdentityException expected) {
                                    assertThat(expected.code()).isEqualTo("EMAIL_IN_USE");
                                }
                            });
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
            worker.runOnce();
            assertThat(count("ACCOUNT")).isEqualTo(1);
            org.mockito.Mockito.verify(mail, times(1)).submit(any(), any(byte[].class));
            when(clock.instant()).thenReturn(start.plusSeconds(60));
            Callable<String> resend =
                    () -> {
                        try {
                            identity.resend("ada@example.com");
                            return "OK";
                        } catch (IdentityException e) {
                            return e.code();
                        }
                    };
            var c = pool.submit(resend);
            var d = pool.submit(resend);
            assertThat(List.of(c.get(15, TimeUnit.SECONDS), d.get(15, TimeUnit.SECONDS)))
                    .containsExactly("OK", "OK");
            worker.runOnce();
            org.mockito.Mockito.verify(mail, times(2)).submit(any(), any(byte[].class));
        }
    }

    @Test
    void accountAndWorkspaceMustBothBeActiveForAccess() throws Exception {
        register("ada@example.com", "company.com");
        verify("ada@example.com");
        var workspace = identity.current("ada@example.com").workspaceId();
        jdbc.update("UPDATE WORKSPACE SET STATUS='PENDING'");
        assertThat(identity.credentials("ada@example.com").active()).isTrue();
        assertThat(identity.current("ada@example.com").workspaces()).isEmpty();
        assertThatThrownBy(() -> identity.current("ada@example.com", workspace))
                .isInstanceOf(IdentityException.class);
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING'");
        assertThat(identity.credentials("ada@example.com").active()).isFalse();
    }

    @Test
    void statusVocabularyCanEvolveWithoutChangingDatabaseConstraints() {
        register("ada@example.com", "company.com");
        verify("ada@example.com");
        // A future application version can introduce statuses without altering these columns.
        assertThat(jdbc.update("UPDATE WORKSPACE SET STATUS='BLOCKED'")).isEqualTo(1);
        assertThat(jdbc.update("UPDATE ACCOUNT SET STATUS='SUSPENDED'")).isEqualTo(1);
        assertThat(jdbc.update("UPDATE VERIFICATION_EMAIL SET STATUS='DEFERRED'")).isEqualTo(1);
    }

    @Test
    void pendingRetentionDoesNotDeleteActiveAccounts() {
        register("active@example.com", "first.com");
        verify("active@example.com");
        register("pending@example.com", "second.com");
        when(clock.instant()).thenReturn(start.plus(Duration.ofDays(31)));
        cleanup.deleteAbandoned();
        assertThat(count("ACCOUNT")).isEqualTo(1);
        assertThat(identity.current("active@example.com").email()).isEqualTo("active@example.com");
    }

    @Test
    void committedClaimExcludesAnotherWorkerAndCoalescesResends() throws Exception {
        var pending = identity.register(request("ada@example.com", "company.com"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var providerId = UUID.randomUUID();
        doAnswer(
                        call -> {
                            entered.countDown();
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            return new ResendClient.Accepted(providerId);
                        })
                .when(mail)
                .submit(any(), any(byte[].class));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(worker::runOnce);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                worker.runOnce();
                assertThat(identity.resend("ada@example.com").emailReference())
                        .isEqualTo(pending.emailReference());
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(identity.deliveryStatus("ada@example.com").deliveryStatus())
                .isEqualTo("PROVIDER_ACCEPTED");
        org.mockito.Mockito.verify(mail, times(1)).submit(eq(pending.emailReference()), any());
    }

    @Test
    void expiredClaimRecoversWithTheOriginalMessageIdAndPayload() {
        var pending = identity.register(request("ada@example.com", "company.com"));
        var delivery =
                jdbc.queryForObject(
                        "SELECT PAYLOAD FROM EMAIL_OUTBOX WHERE ID = ?",
                        String.class,
                        pending.emailReference());
        jdbc.update(
                """
                UPDATE EMAIL_OUTBOX SET SUBMISSION_IN_FLIGHT = TRUE, ATTEMPTS = 1,
                    FIRST_ATTEMPT_AT = ?, NEXT_SUBMISSION_AT = ? WHERE ID = ?
                """,
                java.sql.Timestamp.from(start),
                java.sql.Timestamp.from(start.plusSeconds(60)),
                pending.emailReference());
        worker.runOnce();
        verifyNoInteractions(mail);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT PAYLOAD FROM EMAIL_OUTBOX WHERE ID = ?",
                                String.class,
                                pending.emailReference()))
                .isEqualTo(delivery);
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        worker.runOnce();
        assertThat(fake.latest("ada@example.com").outboxId()).isEqualTo(pending.emailReference());
        assertThat(identity.deliveryStatus("ada@example.com").deliveryStatus())
                .isEqualTo("DELIVERED");
    }


    @Test
    void retryBackoffDoesNotBlockOtherEmailsAndActivatedAccountsAreCancelled() {
        var failed = identity.register(request("failing@example.com", "first.com"));
        identity.register(request("working@example.com", "second.com"));
        doReturn(TestEmailDelivery.temporary())
                .when(mail)
                .submit(eq(failed.emailReference()), any());
        worker.runOnce();
        assertThat(identity.deliveryStatus("working@example.com").deliveryStatus())
                .isEqualTo("DELIVERED");
        assertThat(identity.deliveryStatus("failing@example.com").deliveryStatus())
                .isEqualTo("PENDING");
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        worker.runOnce();
        when(clock.instant()).thenReturn(start.plusSeconds(179));
        worker.runOnce();
        org.mockito.Mockito.verify(mail, times(2)).submit(eq(failed.emailReference()), any());
        identity.resend("working@example.com");
        verify("working@example.com");
        worker.runOnce();
        assertThat(identity.deliveryStatus("working@example.com").deliveryStatus())
                .isEqualTo("CANCELLED");
        when(clock.instant()).thenReturn(start.plus(Duration.ofHours(23)));
        worker.runOnce();
        assertThat(identity.deliveryStatus("failing@example.com").deliveryStatus())
                .isEqualTo("FAILED");
        org.mockito.Mockito.verify(mail, times(2)).submit(eq(failed.emailReference()), any());
    }

    @Test
    void deliveryStatusRequiresCsrfAndReturnsQueueState() throws Exception {
        identity.register(request("ada@example.com", "company.com"));
        mvc.perform(
                        post("/api/registration/status")
                                .contentType("application/json")
                                .content("{\"email\":\"ada@example.com\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/registration/status")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"email\":\"ada@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryStatus").value("PENDING"));
    }

    @Test
    void lostAcceptanceCommitRetriesWithIdenticalPayload() {
        var pending = identity.register(request("ada@example.com", "company.com"));
        var capture = org.mockito.ArgumentCaptor.forClass(byte[].class);
        doReturn(new ResendClient.Accepted(UUID.randomUUID()))
                .when(mail)
                .submit(eq(pending.emailReference()), any());
        jdbc.execute(
                """
                CREATE FUNCTION reject_email_completion() RETURNS TRIGGER AS $$
                BEGIN
                    IF NEW.STATUS = 'PROVIDER_ACCEPTED' THEN
                        RAISE EXCEPTION 'Simulated completion failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute(
                """
                CREATE TRIGGER reject_email_completion BEFORE UPDATE ON EMAIL_OUTBOX
                FOR EACH ROW EXECUTE FUNCTION reject_email_completion()
                """);
        try {
            assertThatThrownBy(worker::runOnce).isInstanceOf(RuntimeException.class);
            assertThat(identity.deliveryStatus("ada@example.com").deliveryStatus())
                    .isEqualTo("PENDING");
            worker.runOnce();
            org.mockito.Mockito.verify(mail, times(1)).submit(eq(pending.emailReference()), any());
        } finally {
            jdbc.execute("DROP TRIGGER reject_email_completion ON EMAIL_OUTBOX");
            jdbc.execute("DROP FUNCTION reject_email_completion()");
        }
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        worker.runOnce();
        org.mockito.Mockito.verify(mail, times(2))
                .submit(eq(pending.emailReference()), capture.capture());
        assertThat(capture.getAllValues().get(0)).isEqualTo(capture.getAllValues().get(1));
        assertThat(identity.deliveryStatus("ada@example.com").deliveryStatus())
                .isEqualTo("PROVIDER_ACCEPTED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidPasswords")
    void rejectsPasswordsOutsideTheRegistrationPolicyWithoutSavingData(String candidate)
            throws Exception {
        var request = new RegistrationRequest("Ada", "ada@example.com", candidate);
        mvc.perform(
                        post("/api/registration")
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.error.message")
                                .value(org.hamcrest.Matchers.containsString("uppercase letter")));
        assertThat(count("ACCOUNT")).isZero();
        assertThat(count("WORKSPACE")).isZero();
        assertThat(count("VERIFICATION_EMAIL")).isZero();
        verifyNoInteractions(mail);
    }

    static java.util.stream.Stream<String> invalidPasswords() {
        return java.util.stream.Stream.of(
                "Abcdefgh!",
                "abcdefghij!",
                "Abcdefghij",
                "Abcdefghi ",
                "Abcdefghéé",
                "A!" + "a".repeat(127));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("validPasswords")
    void acceptsBoundaryPasswordsAndUnicodeUppercaseAndSymbols(String candidate) {
        identity.register(new RegistrationRequest("Ada", "ada@example.com", candidate));
        assertThat(count("ACCOUNT")).isEqualTo(1);
    }

    static java.util.stream.Stream<String> validPasswords() {
        return java.util.stream.Stream.of(
                "Abcdefghi!", "A!" + "a".repeat(126), "Éabcdefg!h", "Abcdefgh€i");
    }

    @Test
    void firstVerificationRotatesSessionAndCsrfAndReplayCannotSignInAgain() throws Exception {
        register("ada@example.com", "company.com");
        var csrfResult = mvc.perform(get("/api/auth/csrf")).andReturn();
        var before = (MockHttpSession) csrfResult.getRequest().getSession(false);
        var oldId = before.getId();
        var oldCsrf = json.readTree(csrfResult.getResponse().getContentAsString()).path("data");
        var result =
                mvc.perform(
                                post("/api/registration/verify")
                                        .session(before)
                                        .header(
                                                oldCsrf.path("headerName").asText(),
                                                oldCsrf.path("token").asText())
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        verificationLink("ada@example.com"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("VERIFIED"))
                        .andExpect(jsonPath("$.data.account.email").value("ada@example.com"))
                        .andExpect(jsonPath("$.data.account.accessRole").value("OWNER"))
                        .andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(
                        post("/api/auth/logout")
                                .session(session)
                                .header(
                                        oldCsrf.path("headerName").asText(),
                                        oldCsrf.path("token").asText()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/registration/verify")
                                .session(session)
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                verificationLink("ada@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ALREADY_VERIFIED"))
                .andExpect(jsonPath("$.data.account.email").value("ada@example.com"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        var replay =
                mvc.perform(
                                post("/api/registration/verify")
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        verificationLink("ada@example.com"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("ALREADY_VERIFIED"))
                        .andExpect(jsonPath("$.data.account").isEmpty())
                        .andReturn();
        mvc.perform(get("/api/auth/me").session((MockHttpSession) replay.getRequest().getSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidVerificationDoesNotAuthenticateTheBrowser() throws Exception {
        register("ada@example.com", "company.com");
        var session = new MockHttpSession();
        var badLink = new Link(fake.latest("ada@example.com").targetId(), "X".repeat(43));
        mvc.perform(
                        post("/api/registration/verify")
                                .session(session)
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(badLink)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(activeWorkspaces()).isZero();
    }

    jakarta.servlet.http.Cookie loginRefresh() throws Exception {
        register("ada@example.com", "company.com");
        verify("ada@example.com");
        var result =
                mvc.perform(
                                post("/api/auth/login")
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "email",
                                                                "ada@example.com",
                                                                "password",
                                                                password))))
                        .andExpect(status().isOk())
                        .andReturn();
        var cookie = result.getResponse().getCookie("THEEVERTHREAD_REFRESH");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("SameSite=Strict");
        assertThat(jdbc.queryForObject("SELECT TOKEN_HASH FROM REFRESH_SESSION", String.class))
                .doesNotContain(cookie.getValue().substring(cookie.getValue().indexOf('.') + 1));
        return cookie;
    }

    @Test
    void expiredSessionRefreshRotatesCookieAndRestoresAccessWithRealCsrf() throws Exception {
        var cookie = loginRefresh();
        // No authenticated JSESSIONID: reproduce an expired browser session.
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        var csrfResponse = mvc.perform(get("/api/auth/csrf")).andReturn();
        var anonymous = (MockHttpSession) csrfResponse.getRequest().getSession();
        var oldId = anonymous.getId();
        var token = json.readTree(csrfResponse.getResponse().getContentAsString()).path("data");
        var result =
                mvc.perform(
                                post("/api/auth/refresh")
                                        .cookie(cookie)
                                        .session(anonymous)
                                        .header(
                                                token.path("headerName").asText(),
                                                token.path("token").asText()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.email").value("ada@example.com"))
                        .andReturn();
        var replacement = result.getResponse().getCookie("THEEVERTHREAD_REFRESH");
        assertThat(replacement.getValue()).isNotEqualTo(cookie.getValue());
        var restored = (MockHttpSession) result.getRequest().getSession();
        assertThat(restored.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/auth/me").session(restored)).andExpect(status().isOk());
        mvc.perform(
                        post("/api/auth/logout")
                                .session(restored)
                                .cookie(replacement)
                                .header(
                                        token.path("headerName").asText(),
                                        token.path("token").asText()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/auth/logout").session(restored).cookie(replacement).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/refresh").cookie(replacement).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRequiresCsrfAndRejectsExpiredMissingAndTamperedCookies() throws Exception {
        var cookie = loginRefresh();
        mvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/refresh").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/auth/refresh")
                                .with(csrf())
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "THEEVERTHREAD_REFRESH", "bad")))
                .andExpect(status().isUnauthorized());
        when(clock.instant()).thenReturn(start.plus(Duration.ofDays(14)));
        mvc.perform(post("/api/auth/refresh").cookie(cookie).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
        assertThat(count("REFRESH_SESSION")).isZero();
    }

    @Test
    void replayRevokesReplacementAndPasswordChangesPreventRefresh() throws Exception {
        var cookie = loginRefresh();
        var first =
                mvc.perform(post("/api/auth/refresh").cookie(cookie).with(csrf()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("THEEVERTHREAD_REFRESH");
        mvc.perform(post("/api/auth/refresh").cookie(cookie).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(first).with(csrf()))
                .andExpect(status().isUnauthorized());
        assertThat(count("REFRESH_SESSION")).isZero();
        var newLogin =
                mvc.perform(
                                post("/api/auth/login")
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "email",
                                                                "ada@example.com",
                                                                "password",
                                                                password))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("THEEVERTHREAD_REFRESH");
        jdbc.update("UPDATE ACCOUNT SET PASSWORD_HASH = 'changed'");
        mvc.perform(post("/api/auth/refresh").cookie(newLogin).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRechecksWorkspaceStatusAndCurrentRole() throws Exception {
        var cookie = loginRefresh();
        var workspace = identity.current("ada@example.com").workspaceId();
        jdbc.update("UPDATE WORKSPACE_MEMBERSHIP SET ROLE = 'MEMBER'");
        var result =
                mvc.perform(post("/api/auth/refresh").cookie(cookie).with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.accessRole").value("MEMBER"))
                        .andReturn();
        mvc.perform(
                        get("/api/invitations")
                                .header("X-Workspace-Id", workspace)
                                .session((MockHttpSession) result.getRequest().getSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_ACCESS_DENIED"));
        jdbc.update("UPDATE WORKSPACE_MEMBERSHIP SET ACTIVE = FALSE");
        assertThatThrownBy(() -> identity.current("ada@example.com", workspace))
                .isInstanceOf(IdentityException.class);
    }

    @Test
    void logoutAfterSessionExpiryStillRevokesRefreshCookie() throws Exception {
        var cookie = loginRefresh();
        mvc.perform(post("/api/auth/logout").cookie(cookie).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("THEEVERTHREAD_REFRESH", 0));
        mvc.perform(post("/api/auth/refresh").cookie(cookie).with(csrf()))
                .andExpect(status().isUnauthorized());
        assertThat(count("REFRESH_SESSION")).isZero();
    }

    @Test
    void concurrentConsumptionCannotIssueTwoReplacementTokens() throws Exception {
        var cookie = loginRefresh();
        var ready = new CountDownLatch(2);
        var startRefresh = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> consume =
                    () -> {
                        ready.countDown();
                        assertThat(startRefresh.await(5, TimeUnit.SECONDS)).isTrue();
                        return mvc.perform(post("/api/auth/refresh").cookie(cookie).with(csrf()))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var first = executor.submit(consume);
            var second = executor.submit(consume);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            startRefresh.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 401);
        }
        assertThat(count("REFRESH_SESSION")).isZero();
    }
}
