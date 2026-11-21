package com.theeverthread.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.theeverthread.identity.api.dto.RegistrationRequest;
import com.theeverthread.identity.api.service.IdentityService;
import com.theeverthread.identity.internal.service.EmailDeliveryWorker;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ResendDeliveryIntegrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    private static final Instant START = Instant.parse("2026-11-21T12:00:00Z");
    private static final String RECIPIENT = "resend-contract@example.com";
    private static final HttpServer provider = startProvider();
    private static final List<Request> requests = new CopyOnWriteArrayList<>();
    private static volatile UUID providerId;
    private static volatile boolean loseFirstResponse;
    private static volatile String lastEvent;

    record Request(String idempotencyKey, byte[] body) {}

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add(
                "theeverthread.email.api-url",
                () -> "http://127.0.0.1:" + provider.getAddress().getPort());
        registry.add("theeverthread.email.api-key", () -> "test-resend");
    }

    @Autowired IdentityService identity;

    @Autowired EmailDeliveryWorker worker;

    @Autowired JdbcTemplate jdbc;

    @Autowired MockMvc mvc;

    @MockitoBean Clock clock;

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE ACCOUNT, WORKSPACE CASCADE");
        requests.clear();
        providerId = UUID.randomUUID();
        loseFirstResponse = false;
        lastEvent = "sent";
        when(clock.instant()).thenReturn(START);
    }

    @AfterAll
    static void stopProvider() {
        provider.stop(0);
    }

    @Test
    void lostSubmissionResponseRetriesWithIdenticalKeyAndBodyAndLookupConfirmsDelivery() {
        loseFirstResponse = true;
        var pending = register();
        assertThat(requests).isEmpty();
        worker.runOnce();
        assertThat(currentStatus()).isEqualTo("PENDING");

        when(clock.instant()).thenReturn(START.plusSeconds(60));
        worker.runOnce();
        assertThat(currentStatus()).isEqualTo("PROVIDER_ACCEPTED");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).idempotencyKey()).isEqualTo("theeverthread/" + pending);
        assertThat(requests.get(1).idempotencyKey()).isEqualTo(requests.get(0).idempotencyKey());
        assertThat(requests.get(1).body()).isEqualTo(requests.get(0).body());
        assertThat(jdbc.queryForObject("SELECT PAYLOAD FROM EMAIL_OUTBOX", String.class)).isNull();

        lastEvent = "delivered";
        when(clock.instant()).thenReturn(START.plusSeconds(360));
        worker.runOnce();
        assertThat(currentStatus()).isEqualTo("DELIVERED");
        var message = TestEmailDelivery.parse(pending, requests.get(0).body());
        assertThat(identity.verify(message.targetId(), message.token()).status())
                .isEqualTo("VERIFIED");
    }

    @Test
    void uncertainSubmissionStopsBeforeTheIdempotencyKeyExpires() throws Exception {
        loseFirstResponse = true;
        var id = register();
        worker.runOnce();
        var deadline = START.plus(Duration.ofHours(23));
        when(clock.instant()).thenReturn(deadline);
        worker.runOnce();

        assertThat(currentStatus()).isEqualTo("UNKNOWN");
        assertThat(requests).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT FAILURE_CODE FROM EMAIL_OUTBOX", String.class))
                .isEqualTo("RETRY_WINDOW_EXHAUSTED");

        var original = TestEmailDelivery.parse(id, requests.getFirst().body());
        var tracked =
                new TestEmailDelivery.Message(
                        id, providerId, RECIPIENT, original.targetId(), original.token());
        notify(
                "msg_after_deadline",
                Long.toString(deadline.getEpochSecond()),
                TestEmailDelivery.payload(tracked, "email.delivered", deadline, Map.of()));
        assertThat(currentStatus()).isEqualTo("DELIVERED");
    }

    @Test
    void signedSuppressionIsRecordedOnceAndOlderDeliveryCannotOverrideIt() throws Exception {
        var id = register();
        worker.runOnce();
        var message = TestEmailDelivery.parse(id, requests.getFirst().body());
        var tracked =
                new TestEmailDelivery.Message(
                        id, providerId, RECIPIENT, message.targetId(), message.token());
        var suppression =
                TestEmailDelivery.payload(
                        tracked,
                        "email.suppressed",
                        START.plusSeconds(2),
                        Map.of(
                                "suppressed",
                                Map.of("message", "Address is on the suppression list")));
        var timestamp = Long.toString(START.getEpochSecond());
        mvc.perform(
                        post("/api/webhooks/resend")
                                .header("svix-id", "msg_suppressed")
                                .header("svix-timestamp", timestamp)
                                .header("svix-signature", "v1,invalid")
                                .content(suppression))
                .andExpect(status().isBadRequest());
        assertThat(currentStatus()).isEqualTo("PROVIDER_ACCEPTED");

        notify("msg_suppressed", timestamp, suppression);
        notify("msg_suppressed", timestamp, suppression);
        notify(
                "msg_older",
                timestamp,
                TestEmailDelivery.payload(
                        tracked, "email.delivered", START.plusSeconds(1), Map.of()));
        assertThat(currentStatus()).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT REASON FROM EMAIL_DELIVERY_EVENT WHERE KIND ="
                                        + " 'email.suppressed'",
                                String.class))
                .containsExactly("Address is on the suppression list");
        assertThat(jdbc.queryForObject("SELECT TOKEN_HASH FROM VERIFICATION_EMAIL", String.class))
                .isNull();
    }

    @Test
    void missingOutcomeBecomesUnknownAndLateWebhookCannotUndoCompletedVerification()
            throws Exception {
        var id = register();
        worker.runOnce();
        var message = TestEmailDelivery.parse(id, requests.getFirst().body());
        assertThat(identity.verify(message.targetId(), message.token()).status())
                .isEqualTo("VERIFIED");

        var now = START.plus(Duration.ofDays(7));
        when(clock.instant()).thenReturn(now);
        worker.runOnce();
        assertThat(currentStatus()).isEqualTo("UNKNOWN");
        var tracked =
                new TestEmailDelivery.Message(
                        id, providerId, RECIPIENT, message.targetId(), message.token());
        notify(
                "msg_late",
                Long.toString(now.getEpochSecond()),
                TestEmailDelivery.payload(
                        tracked,
                        "email.bounced",
                        START.plusSeconds(3),
                        Map.of("bounce", Map.of("message", "Mailbox unavailable"))));
        assertThat(currentStatus()).isEqualTo("FAILED");
        assertThat(identity.credentials(RECIPIENT).active()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM WORKSPACE", Integer.class))
                .isEqualTo(1);
    }

    private void notify(String id, String timestamp, byte[] body) throws Exception {
        mvc.perform(
                        post("/api/webhooks/resend")
                                .header("svix-id", id)
                                .header("svix-timestamp", timestamp)
                                .header(
                                        "svix-signature",
                                        TestEmailDelivery.signature(id, timestamp, body))
                                .content(body))
                .andExpect(status().isOk());
    }

    private UUID register() {
        return identity.register(new RegistrationRequest("Resend Test", RECIPIENT, "Validpass!"))
                .emailReference();
    }

    private String currentStatus() {
        return identity.deliveryStatus(RECIPIENT).deliveryStatus();
    }

    private static HttpServer startProvider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/emails", ResendDeliveryIntegrationTests::respond);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start local Resend test server", exception);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"Bearer test-resend"
                    .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 403, "{}");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                requests.add(
                        new Request(
                                exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                                exchange.getRequestBody().readAllBytes()));
                if (loseFirstResponse && requests.size() == 1) {
                    respond(exchange, 503, "{\"name\":\"internal_server_error\"}");
                    return;
                }
                respond(exchange, 200, "{\"id\":\"" + providerId + "\"}");
            } else if (("/emails/" + providerId).equals(exchange.getRequestURI().getPath())) {
                respond(
                        exchange,
                        200,
                        "{\"id\":\"" + providerId + "\",\"last_event\":\"" + lastEvent + "\"}");
            } else {
                respond(exchange, 404, "{}");
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
