package com.theeverthread.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.theeverthread.identity.api.dto.*;
import com.theeverthread.identity.api.exception.*;
import com.theeverthread.identity.api.service.*;
import com.theeverthread.identity.internal.service.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
@SpringBootTest(properties = "theeverthread.registration.delivery.enabled=false")
@AutoConfigureMockMvc
class InvitationIntegrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired
    IdentityService identity;
    @Autowired
    InvitationService invitations;
    @Autowired EmailDeliveryWorker worker;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;
    @MockitoBean ResendClient mail;
    @Autowired ResendWebhook webhook;
    TestEmailDelivery fake;
    @MockitoBean
    Clock clock;
    final Instant now = Instant.parse("2026-10-07T08:00:00Z");

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE TEAM_INVITATION, VERIFICATION_EMAIL, ACCOUNT, WORKSPACE CASCADE");
        when(clock.instant()).thenReturn(now);
        fake = new TestEmailDelivery(mail, webhook, clock);
        admin("admin@example.com", "acme.com");
    }

    void admin(String email, String domain) {
        identity.register(new RegistrationRequest("Ada", email, "Validpass!"));
        worker.runOnce();
        var message = fake.latest(email);
        identity.verify(message.targetId(), message.token());
        clearInvocations(mail);
    }

    InvitationView send(String email) {
        return sendAs("admin@example.com", email);
    }

    InvitationView sendAs(String actor, String email) {
        var workspace = identity.current(actor).workspaceId();
        var request = new InvitationRequest(email);
        invitations.send(actor, workspace, request);
        worker.runOnce();
        return invitations.status(actor, workspace, request);
    }

    InvitationView latest(String email) {
        return invitations.status(
                "admin@example.com",
                identity.current("admin@example.com").workspaceId(),
                new InvitationRequest(email));
    }

    InvitationView resend(Long id, String email) {
        invitations.resend(
                "admin@example.com", identity.current("admin@example.com").workspaceId(), id);
        worker.runOnce();
        return latest(email);
    }

    void rejected(String email, String code) {
        assertThatThrownBy(() -> send(email))
                .isInstanceOfSatisfying(
                        IdentityException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void sendsPersonalAddressWithoutCreatingMembershipAndStoresOnlyHashWithSevenDayExpiry() {
        var result = send("Person@gmail.com");
        assertThat(result.email()).isEqualTo("person@gmail.com");
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.expiresAt()).isEqualTo(now.plus(Duration.ofDays(7)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Integer.class)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT LENGTH(TOKEN_HASH) FROM TEAM_INVITATION", Integer.class))
                .isEqualTo(64);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT PROVIDER_ID FROM EMAIL_OUTBOX WHERE ID=?",
                                UUID.class,
                                result.emailReference()))
                .isEqualTo(fake.latest(result.email()).providerId());
        rejected("PERSON@gmail.com", "INVITATION_PENDING");
        verify(mail, times(1)).submit(any(), any(byte[].class));
    }

    @Test
    void membershipWinsOverPendingAndSelfAndOtherWorkspaceAreRejected() {
        rejected("admin@example.com", "ALREADY_MEMBER");
        admin("other@example.com", "other.com");
        assertThat(send("other@example.com").status()).isEqualTo("READY");
        assertThat(identity.current("other@example.com").workspaces()).hasSize(1);
        rejected("other@example.com", "INVITATION_PENDING");
        verify(mail, times(1)).submit(any(), any(byte[].class));
    }

    @Test
    void pendingOtherWorkspaceDoesNotReserveMembershipAndExpiredOrRevokedCanBeReplaced() {
        send("new@example.com");
        admin("other@example.com", "other.com");
        assertThat(sendAs("other@example.com", "new@example.com").status()).isEqualTo("READY");
        when(clock.instant()).thenReturn(now.plus(Duration.ofDays(7)));
        send("new@example.com");
        jdbc.update(
                "UPDATE TEAM_INVITATION SET STATUS='REVOKED' WHERE WORKSPACE_ID=(SELECT"
                        + " DEFAULT_WORKSPACE_ID FROM ACCOUNT WHERE EMAIL='admin@example.com')");
        send("new@example.com");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM_INVITATION", Integer.class))
                .isEqualTo(2);
        verify(mail, times(3)).submit(any(), any(byte[].class));
    }

    @Test
    void configurableCapCountsAdminButNotInvitationsAndAlsoBlocksFreshExpiredInvitations() {
        jdbc.update("UPDATE WORKSPACE SET MAX_ACTIVE_ACCOUNTS=2");
        for (int i = 0; i < 4; i++) send("person" + i + "@gmail.com");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE WORKSPACE SET MAX_ACTIVE_ACCOUNTS=1");
        assertThatThrownBy(() -> send("fresh@example.com"))
                .hasMessage(
                        "Your workspace has reached its limit of 1 active accounts. You cannot"
                                + " send invitations.");
        when(clock.instant()).thenReturn(now.plus(Duration.ofDays(8)));
        rejected("person0@gmail.com", "ACCOUNT_LIMIT");
        verify(mail, times(4)).submit(any(), any(byte[].class));
    }

    @Test
    void defaultCapIncludesAdministrator() {
        var workspace = identity.current("admin@example.com").workspaceId();
        for (int i = 0; i < 9; i++)
            seedMember("member" + i + "@example.com", workspace, now.plusSeconds(i));
        assertThatThrownBy(() -> send("fresh@example.com"))
                .hasMessageContaining("limit of 10 active accounts");
        verifyNoInteractions(mail);
    }

    @Test
    void failedSendLeavesNoUsableLinkAndExplicitRetryCreatesOneNewMessage() {
        doReturn(TestEmailDelivery.rejected()).when(mail).submit(any(), any(byte[].class));
        var failed = send("new@example.com");
        assertThat(failed.deliveryStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT TOKEN_HASH FROM TEAM_INVITATION", String.class))
                .isNull();
        fake = new TestEmailDelivery(mail, webhook, clock);
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertThat(resend(failed.id(), failed.email()).deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM_INVITATION", Integer.class))
                .isEqualTo(1);
    }


    @Test
    void simultaneousRequestsCommitOneAttemptAndNeverSendDuplicateEmail() throws Exception {
        var workspace = identity.current("admin@example.com").workspaceId();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    pool.submit(
                            () ->
                                    invitations.send(
                                            "admin@example.com",
                                            workspace,
                                            new InvitationRequest("new@example.com")));
            var second =
                    pool.submit(
                            () ->
                                    invitations.send(
                                            "admin@example.com",
                                            workspace,
                                            new InvitationRequest("NEW@example.com")));
            assertThat(first.get(10, TimeUnit.SECONDS).emailReference())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).emailReference());
        }
        assertThat(latest("new@example.com").deliveryStatus()).isEqualTo("PENDING");
        verifyNoInteractions(mail);
        worker.runOnce();
        assertThat(latest("new@example.com").deliveryStatus()).isEqualTo("DELIVERED");
        verify(mail, times(1)).submit(any(), any(byte[].class));
    }

    @Test
    void invitationRollsBackIfTheEmailObligationCannotBeSaved() {
        jdbc.execute(
                "ALTER TABLE EMAIL_OUTBOX ADD CONSTRAINT reject_invite"
                        + " CHECK (INVITATION_ID IS NULL)");
        try {
            assertThatThrownBy(() -> send("new@example.com")).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM_INVITATION", Integer.class))
                    .isZero();
            verifyNoInteractions(mail);
        } finally {
            jdbc.execute("ALTER TABLE EMAIL_OUTBOX DROP CONSTRAINT reject_invite");
        }
    }

    @Test
    void serviceAndHttpRecheckRoleVerificationWorkspaceOwnershipCsrfAndInput() throws Exception {
        mvc.perform(
                        post("/api/invitations")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"email\":\"new@gmail.com\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/invitations")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .header(
                                        "X-Workspace-Id",
                                        identity.current("admin@example.com").workspaceId())
                                .contentType("application/json")
                                .content("{\"email\":\"new@gmail.com\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/invitations")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .header(
                                        "X-Workspace-Id",
                                        identity.current("admin@example.com").workspaceId())
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/invitations")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .header(
                                        "X-Workspace-Id",
                                        identity.current("admin@example.com").workspaceId())
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"email\":\"new@gmail.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryStatus").value("PENDING"));
        admin("other@example.com", "other.com");
        mvc.perform(
                        post("/api/invitations/status")
                                .with(user("other@example.com").roles("ADMIN"))
                                .header(
                                        "X-Workspace-Id",
                                        identity.current("other@example.com").workspaceId())
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"email\":\"new@gmail.com\"}"))
                .andExpect(status().isNotFound());
        jdbc.update(
                "UPDATE WORKSPACE_MEMBERSHIP SET ROLE='MEMBER' WHERE ACCOUNT_ID=(SELECT ID FROM"
                        + " ACCOUNT WHERE EMAIL='admin@example.com')");
        assertThatThrownBy(() -> send("fresh@example.com"))
                .isInstanceOfSatisfying(
                        IdentityException.class, e -> assertThat(e.status()).isEqualTo(403));
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING' WHERE" + " EMAIL='admin@example.com'");
        assertThatThrownBy(() -> send("fresh@example.com"))
                .hasMessageContaining("Verify your email");
    }

    @Test
    void historySurvivesNavigationAndIsScopedToTheVerifiedAdministratorsWorkspace()
            throws Exception {
        assertThat(
                        invitations
                                .history(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId(),
                                        0)
                                .invitations())
                .isEmpty();
        var saved = send("saved@gmail.com");
        admin("other@example.com", "other.com");
        invitations.send(
                "other@example.com",
                identity.current("other@example.com").workspaceId(),
                new InvitationRequest("other-recipient@gmail.com"));
        assertThat(
                        invitations
                                .history(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId(),
                                        0)
                                .invitations())
                .extracting(InvitationView::id)
                .containsExactly(saved.id());
        mvc.perform(
                        get("/api/invitations")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .header(
                                        "X-Workspace-Id",
                                        identity.current("admin@example.com").workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitations[0].email").value("saved@gmail.com"))
                .andExpect(jsonPath("$.data.invitations[0].status").value("READY"))
                .andExpect(jsonPath("$.data.invitations[0].tokenHash").doesNotExist())
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void historyIsBoundedAndPaginatesOlderInvitations() {
        for (int i = 0; i < 26; i++) {
            when(clock.instant()).thenReturn(now.plusSeconds(i));
            send("recipient" + i + "@gmail.com");
        }
        var first =
                invitations.history(
                        "admin@example.com",
                        identity.current("admin@example.com").workspaceId(),
                        0);
        var second =
                invitations.history(
                        "admin@example.com",
                        identity.current("admin@example.com").workspaceId(),
                        1);
        assertThat(first.invitations()).hasSize(25);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.invitations()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(first.invitations().getFirst().email()).isEqualTo("recipient25@gmail.com");
        assertThat(second.invitations().getFirst().email()).isEqualTo("recipient0@gmail.com");
    }

    @Test
    void historyRejectsAnonymousUnverifiedAndNonAdministratorAccessAndInvalidPages()
            throws Exception {
        mvc.perform(get("/api/invitations")).andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/api/invitations")
                                .with(user("admin@example.com").roles("MEMBER"))
                                .header(
                                        "X-Workspace-Id",
                                        identity.current("admin@example.com").workspaceId()))
                .andExpect(status().isOk());
        mvc.perform(
                        get("/api/invitations")
                                .param("page", "-1")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .header(
                                        "X-Workspace-Id",
                                        identity.current("admin@example.com").workspaceId()))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING' WHERE EMAIL='admin@example.com'");
        assertThatThrownBy(
                        () ->
                                invitations.history(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId(),
                                        0))
                .isInstanceOfSatisfying(
                        IdentityException.class,
                        error -> assertThat(error.status()).isEqualTo(403));
    }

    @Autowired

    InvitationAcceptanceService acceptance;

    @Test
    void resendCooldownAndDeliveredReplacementRotateLinkButBouncePreservesPreviousTerms() {
        var original = send("resend@example.com");
        var first = fake.latest(original.email());
        assertThat(original.sentAt()).isEqualTo(now);
        assertThatThrownBy(() -> resend(original.id(), original.email()))
                .isInstanceOfSatisfying(
                        IdentityException.class,
                        error -> assertThat(error.code()).isEqualTo("RESEND_COOLDOWN"));

        when(clock.instant()).thenReturn(now.plusSeconds(60));
        var replacement = resend(original.id(), original.email());
        var second = fake.latest(original.email());
        assertThat(replacement.expiresAt()).isEqualTo(now.plusSeconds(60).plus(Duration.ofDays(7)));
        assertThat(replacement.sentAt()).isEqualTo(now.plusSeconds(60));
        assertThat(
                        acceptance
                                .preview(new InvitationLinkRequest(original.id(), first.token()))
                                .status())
                .isEqualTo("INVALID_INVITATION");

        when(clock.instant()).thenReturn(now.plusSeconds(120));
        var queued =
                invitations.resend(
                        "admin@example.com",
                        identity.current("admin@example.com").workspaceId(),
                        original.id());
        var third =
                new TestEmailDelivery.Message(
                        queued.emailReference(),
                        UUID.randomUUID(),
                        original.email(),
                        original.id(),
                        "");
        doReturn(new ResendClient.Accepted(third.providerId()))
                .when(mail)
                .submit(eq(queued.emailReference()), any(byte[].class));
        worker.runOnce();
        fake.event(
                third,
                "email.bounced",
                now.plusSeconds(122),
                Map.of("bounce", Map.of("message", "Mailbox unavailable")));
        fake.event(second, "email.delivered", now.plusSeconds(121), Map.of());
        fake.event(third, "email.delivered", now.plusSeconds(121), Map.of());

        var failed = latest(original.email());
        assertThat(failed.deliveryStatus()).isEqualTo("FAILED");
        assertThat(failed.emailReference()).isEqualTo(queued.emailReference());
        assertThat(failed.sentAt()).isEqualTo(replacement.sentAt());
        assertThat(failed.expiresAt()).isEqualTo(replacement.expiresAt());
        assertThat(
                        acceptance
                                .preview(new InvitationLinkRequest(original.id(), second.token()))
                                .status())
                .isEqualTo("READY");
    }

    @Test
    void failedReplacementKeepsEarlierAcceptedButUnconfirmedLinkUsable() {
        String recipient = "unconfirmed@example.com";
        var workspace = identity.current("admin@example.com").workspaceId();
        var original =
                invitations.send("admin@example.com", workspace, new InvitationRequest(recipient));
        var messages = new ArrayList<TestEmailDelivery.Message>();
        doAnswer(
                        call -> {
                            var message =
                                    TestEmailDelivery.parse(
                                            call.getArgument(0), call.getArgument(1));
                            messages.add(message);
                            return new ResendClient.Accepted(message.providerId());
                        })
                .when(mail)
                .submit(eq(original.emailReference()), any(byte[].class));
        worker.runOnce();

        assertThat(latest(recipient).deliveryStatus()).isEqualTo("PROVIDER_ACCEPTED");
        var link = new InvitationLinkRequest(original.id(), messages.getFirst().token());
        assertThat(acceptance.preview(link).status()).isEqualTo("READY");
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        var replacement = invitations.resend("admin@example.com", workspace, original.id());
        doReturn(TestEmailDelivery.rejected())
                .when(mail)
                .submit(eq(replacement.emailReference()), any(byte[].class));
        worker.runOnce();
        assertThat(latest(recipient).deliveryStatus()).isEqualTo("FAILED");
        fake.event(messages.getFirst(), "email.delivered", now.plusSeconds(1), Map.of());
        assertThat(latest(recipient).deliveryStatus()).isEqualTo("FAILED");
        assertThat(acceptance.preview(link).status()).isEqualTo("READY");
        rejected(recipient, "INVITATION_PENDING");

        jdbc.update("UPDATE TEAM_INVITATION SET STATUS='REVOKED' WHERE ID=?", original.id());
        send(recipient);
        assertThat(acceptance.preview(link).status()).isEqualTo("INVALID_INVITATION");
    }

    @Test
    void laterBounceOfAReplacementRestoresOnlyTheImmediatelyPreviousLink() {
        var original = send("late-bounce@example.com");
        var first = fake.message(original.emailReference());
        when(clock.instant()).thenReturn(now.plusSeconds(60));

        var replacement = resend(original.id(), original.email());
        assertThat(replacement.deliveryStatus()).isEqualTo("DELIVERED");
        var second = fake.message(replacement.emailReference());
        assertThat(
                        acceptance
                                .preview(new InvitationLinkRequest(original.id(), first.token()))
                                .status())
                .isEqualTo("INVALID_INVITATION");

        fake.event(
                second,
                "email.bounced",
                now.plusSeconds(61),
                Map.of("bounce", Map.of("message", "Mailbox rejected after handoff")));
        var failed = latest(original.email());
        assertThat(failed.deliveryStatus()).isEqualTo("FAILED");
        assertThat(failed.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(
                        acceptance
                                .preview(new InvitationLinkRequest(original.id(), first.token()))
                                .status())
                .isEqualTo("READY");
        assertThat(
                        acceptance
                                .preview(new InvitationLinkRequest(original.id(), second.token()))
                                .status())
                .isEqualTo("INVALID_INVITATION");
    }

    @Test
    void failedInvitationsAreListedWithNoSentDateAndRetryChecksCapacityAndEligibility() {
        doReturn(TestEmailDelivery.rejected()).when(mail).submit(any(), any(byte[].class));
        var failed = send("retry@example.com");
        assertThat(
                        invitations
                                .sent(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId())
                                .getFirst()
                                .sentAt())
                .isNull();
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        jdbc.update("UPDATE WORKSPACE SET MAX_ACTIVE_ACCOUNTS=1");
        assertThatThrownBy(
                        () ->
                                invitations.resend(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId(),
                                        failed.id()))
                .hasMessageContaining("limit of 1 active accounts");
        jdbc.update("UPDATE WORKSPACE SET MAX_ACTIVE_ACCOUNTS=10");
        fake = new TestEmailDelivery(mail, webhook, clock);
        assertThat(resend(failed.id(), failed.email()).deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM_INVITATION", Integer.class))
                .isEqualTo(1);
        admin("other@example.com", "other.com");
        when(clock.instant()).thenReturn(now.plusSeconds(120));
        assertThatThrownBy(
                        () ->
                                invitations.resend(
                                        "other@example.com",
                                        identity.current("other@example.com").workspaceId(),
                                        failed.id()))
                .isInstanceOfSatisfying(
                        IdentityException.class, e -> assertThat(e.status()).isEqualTo(404));
        jdbc.update("UPDATE ACCOUNT SET EMAIL='retry@example.com' WHERE EMAIL='other@example.com'");
        assertThat(
                        invitations
                                .resend(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId(),
                                        failed.id())
                                .deliveryStatus())
                .isEqualTo("PENDING");
    }


    @Test
    void acceptanceDuringDeliveryWinsAndCannotBeReopened() {
        var original = send("accepted@example.com");
        var token = fake.latest(original.email()).token();
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        var queued =
                invitations.resend(
                        "admin@example.com",
                        identity.current("admin@example.com").workspaceId(),
                        original.id());
        doAnswer(
                        call -> {
                            var message =
                                    TestEmailDelivery.parse(
                                            call.getArgument(0), call.getArgument(1));
                            acceptance.accept(
                                    new MemberRegistrationRequest(
                                            new InvitationLinkRequest(original.id(), token),
                                            "Member Name",
                                            "Validpass!"),
                                    null);
                            fake.event(message, "email.delivered", clock.instant(), Map.of());
                            return new ResendClient.Accepted(message.providerId());
                        })
                .when(mail)
                .submit(eq(queued.emailReference()), any(byte[].class));
        worker.runOnce();
        var accepted = latest(original.email());
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(accepted.sentAt()).isEqualTo(original.sentAt());
        assertThat(accepted.deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(
                        invitations.members(
                                "admin@example.com",
                                identity.current("admin@example.com").workspaceId()))
                .containsExactly(
                        new ActiveMemberView("Member Name", "accepted@example.com", "MEMBER"),
                        new ActiveMemberView("Ada", "admin@example.com", "OWNER"));
        assertThatThrownBy(() -> resend(original.id(), original.email()))
                .isInstanceOf(IdentityException.class);
    }

    @Test
    void listsAreCompleteOrderedWorkspaceScopedAndExcludeUnusableLinks() throws Exception {
        for (int i = 0; i < 30; i++) {
            when(clock.instant()).thenReturn(now.plusSeconds(i));
            send("listed" + i + "@example.com");
        }
        assertThat(
                        invitations.sent(
                                "admin@example.com",
                                identity.current("admin@example.com").workspaceId()))
                .hasSize(30);
        assertThat(
                        invitations
                                .sent(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId())
                                .getFirst()
                                .email())
                .isEqualTo("listed29@example.com");
        jdbc.update(
                "UPDATE TEAM_INVITATION SET STATUS='REVOKED' WHERE EMAIL='listed0@example.com'");
        jdbc.update(
                "UPDATE TEAM_INVITATION SET EXPIRES_AT=? WHERE EMAIL='listed1@example.com'",
                java.sql.Timestamp.from(now));
        assertThat(
                        invitations.sent(
                                "admin@example.com",
                                identity.current("admin@example.com").workspaceId()))
                .hasSize(28);
        assertThat(
                        invitations.members(
                                "admin@example.com",
                                identity.current("admin@example.com").workspaceId()))
                .containsExactly(new ActiveMemberView("Ada", "admin@example.com", "OWNER"));
        admin("other@example.com", "other.com");
        assertThat(
                        invitations.sent(
                                "other@example.com",
                                identity.current("other@example.com").workspaceId()))
                .isEmpty();
        mvc.perform(get("/api/invitations/sent")).andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/api/invitations/members")
                                .with(user("admin@example.com").roles("MEMBER"))
                                .header(
                                        "X-Workspace-Id",
                                        identity.current("admin@example.com").workspaceId()))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/invitations/"
                                        + invitations
                                                .sent(
                                                        "admin@example.com",
                                                        identity.current("admin@example.com")
                                                                .workspaceId())
                                                .getFirst()
                                                .id()
                                        + "/resend")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .header(
                                        "X-Workspace-Id",
                                        identity.current("admin@example.com").workspaceId()))
                .andExpect(status().isForbidden());
        jdbc.update(
                "UPDATE WORKSPACE_MEMBERSHIP SET ROLE='MEMBER' WHERE ACCOUNT_ID=(SELECT ID FROM"
                        + " ACCOUNT WHERE EMAIL='admin@example.com')");
        assertThatThrownBy(
                        () ->
                                invitations.sent(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId()))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(
                        () ->
                                invitations.members(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId()))
                .isInstanceOf(IdentityException.class);
    }

    @Test
    void concurrentResendQueuesOneReplacementAndOldLinkWorksDuringDelivery() throws Exception {
        var original = send("race@example.com");
        var token = fake.latest(original.email()).token();
        var workspace = identity.current("admin@example.com").workspaceId();
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<String> request =
                    () -> {
                        try {
                            return invitations
                                    .resend("admin@example.com", workspace, original.id())
                                    .deliveryStatus();
                        } catch (IdentityException failure) {
                            return failure.code();
                        }
                    };
            var first = pool.submit(request);
            var second = pool.submit(request);
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("PENDING", "RESEND_IN_PROGRESS");
        }
        assertThat(acceptance.preview(new InvitationLinkRequest(original.id(), token)).status())
                .isEqualTo("READY");
        worker.runOnce();
        assertThat(latest(original.email()).deliveryStatus()).isEqualTo("DELIVERED");
        verify(mail, times(2)).submit(any(), any(byte[].class));
    }

    @Test
    void memberListIncludesAdministratorsAndExcludesInactiveAccountsAndOtherCompanies() {
        var org = identity.current("admin@example.com").workspaceId();
        for (int i = 0; i < 30; i++)
            seedMember("member" + i + "@example.com", org, now.plusSeconds(i));
        assertThat(
                        invitations.members(
                                "admin@example.com",
                                identity.current("admin@example.com").workspaceId()))
                .hasSize(31);
        assertThat(
                        invitations
                                .members(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId())
                                .getFirst()
                                .email())
                .isEqualTo("member29@example.com");
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING' WHERE EMAIL='member0@example.com'");
        admin("other@example.com", "other.com");
        assertThat(
                        invitations.members(
                                "admin@example.com",
                                identity.current("admin@example.com").workspaceId()))
                .hasSize(30);
        assertThat(
                        invitations.members(
                                "admin@example.com",
                                identity.current("admin@example.com").workspaceId()))
                .extracting(ActiveMemberView::email)
                .contains("admin@example.com")
                .doesNotContain("other@example.com", "member0@example.com");
        assertThat(
                        invitations.members(
                                "other@example.com",
                                identity.current("other@example.com").workspaceId()))
                .containsExactly(new ActiveMemberView("Ada", "other@example.com", "OWNER"));
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING' WHERE EMAIL='admin@example.com'");
        assertThatThrownBy(
                        () ->
                                invitations.members(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId()))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(
                        () ->
                                invitations.resend(
                                        "admin@example.com",
                                        identity.current("admin@example.com").workspaceId(),
                                        Long.MAX_VALUE))
                .isInstanceOf(IdentityException.class);
    }

    void seedMember(String email, Long workspace, Instant updatedAt) {
        var id =
                jdbc.queryForObject(
                        "INSERT INTO ACCOUNT(EMAIL, FULL_NAME, PASSWORD_HASH, STATUS,"
                            + " DEFAULT_WORKSPACE_ID, UPDATED_AT) VALUES (?, 'Member', 'unused',"
                            + " 'ACTIVE', ?, ?) RETURNING ID",
                        Long.class,
                        email,
                        workspace,
                        java.sql.Timestamp.from(updatedAt));
        jdbc.update(
                "INSERT INTO WORKSPACE_MEMBERSHIP(ACCOUNT_ID, WORKSPACE_ID, ROLE) VALUES (?, ?,"
                    + " 'MEMBER')",
                id,
                workspace);
    }
}
