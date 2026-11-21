package com.theeverthread.identity.internal.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.theeverthread.identity.api.dto.*;
import com.theeverthread.identity.api.service.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@SpringBootTest(
        properties = {
            "email.provider=resend",
            "email.initial-delay=3600000",
            "RESEND_API_KEY=unit-test",
            "RESEND_WEBHOOK_SECRET=whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "EMAIL_PAYLOAD_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
@Testcontainers
@AutoConfigureMockMvc
class ResendDeliveryIntegrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void connect(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", database::getJdbcUrl);
        r.add("spring.datasource.username", database::getUsername);
        r.add("spring.datasource.password", database::getPassword);
    }

    @Autowired EmailDispatcher worker;
    @Autowired JdbcTemplate db;
    @Autowired IdentityService identity;
    @Autowired InvitationService invitations;
    @Autowired TransactionTemplate transactions;
    @Autowired JsonMapper json;
    @Autowired MockMvc mvc;
    @MockitoSpyBean ResendProvider provider;
    private final RegistrationRequest request =
            new RegistrationRequest("Ada", "ada@example.com", "Validpass!");

    @BeforeEach
    void resetDatabase() {
        db.execute("TRUNCATE account,workspace,email_event CASCADE");
    }

    @Test
    void rollsBackEmailWithAccount() {
        transactions.executeWithoutResult(
                tx -> {
                    identity.register(request);
                    tx.setRollbackOnly();
                });
        assertThat(db.queryForObject("SELECT count(*) FROM email_outbox", Integer.class)).isZero();
    }

    @Test
    void expiredClaimRecoversButAnAmbiguousDayOldRequestStops() {
        identity.register(request);
        db.update(
                "UPDATE email_outbox SET"
                    + " state='SENDING',claim=gen_random_uuid(),first_attempt=now()-interval '23"
                    + " hours 1 minute',next_attempt=now()-interval '1 minute'");
        worker.send();
        verify(provider, never()).submit(any(), anyString());
        assertThat(identity.deliveryStatus(request.email()).deliveryStatus()).isEqualTo("REVIEW");
    }

    @Test
    void lostReplyKeepsOriginalMessageAndMissingWebhookIsReconciled() throws Exception {
        var keys = new ArrayList<UUID>();
        var bodies = new ArrayList<String>();
        String id = UUID.randomUUID().toString();
        doAnswer(
                        call -> {
                            keys.add(call.getArgument(0));
                            bodies.add(call.getArgument(1));
                            if (keys.size() == 1) throw new ResourceAccessException("timeout");
                            return id;
                        })
                .when(provider)
                .submit(any(), anyString());
        assertThat(identity.register(request).deliveryStatus()).isEqualTo("QUEUED");
        worker.send();
        db.update("UPDATE email_outbox SET next_attempt=now()");
        worker.send();
        assertThat(keys).hasSize(2);
        assertThat(keys.getFirst()).isEqualTo(keys.getLast());
        assertThat(bodies.getFirst()).isEqualTo(bodies.getLast());
        String link = json.readTree(bodies.getFirst()).path("text").asString().split("#")[1];
        assertThat(identity.deliveryStatus(request.email()).deliveryStatus()).isEqualTo("ACCEPTED");
        db.update("UPDATE email_outbox SET next_check=now()");
        doReturn("delivered").when(provider).lookup(id);
        worker.reconcile();
        assertThat(identity.deliveryStatus(request.email()).deliveryStatus())
                .isEqualTo("DELIVERED");
        var verified = identity.verify(Long.parseLong(link.split("\\.")[0]), link.split("\\.")[1]);
        var workspace = verified.account().workspaceId();
        var invitation =
                invitations.send(
                        request.email(), workspace, new InvitationRequest("sara@example.com"));
        String messageKey =
                db.queryForObject(
                        "SELECT message_key FROM email_outbox WHERE invitation_id=?",
                        String.class,
                        invitation.id());
        String emailId = UUID.randomUUID().toString();
        String body =
                json.writeValueAsString(
                        Map.of(
                                "type",
                                "email.bounced",
                                "created_at",
                                Instant.now().toString(),
                                "data",
                                Map.of(
                                        "email_id",
                                        emailId,
                                        "tags",
                                        Map.of("message_key", messageKey),
                                        "bounce",
                                        Map.of("message", "Mailbox rejected"))));
        String stamp = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        String signature =
                "v1,"
                        + Base64.getEncoder()
                                .encodeToString(
                                        mac.doFinal(
                                                ("notice." + stamp + "." + body)
                                                        .getBytes(StandardCharsets.UTF_8)));
        for (String sig : List.of("v1,invalid", signature, signature)) {
            mvc.perform(
                            post("/api/webhooks/resend")
                                    .contentType("application/json")
                                    .content(body)
                                    .header("svix-id", "notice")
                                    .header("svix-timestamp", stamp)
                                    .header("svix-signature", sig))
                    .andExpect(status().is(sig.equals(signature) ? 204 : 401));
        }
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM email_event WHERE event_key='notice'",
                                Integer.class))
                .isOne();
        assertThat(
                        invitations
                                .status(
                                        request.email(),
                                        workspace,
                                        new InvitationRequest("sara@example.com"))
                                .message())
                .contains("bounced");
    }
}
