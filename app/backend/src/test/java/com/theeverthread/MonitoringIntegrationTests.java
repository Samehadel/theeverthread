package com.theeverthread;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MonitoringIntegrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");
    static final Path fixture = createIdentities();
    @LocalServerPort int applicationPort;
    @Autowired Connector metricsConnector;

    @AfterAll
    static void removeTestCredentials() throws Exception {
        try (var paths = Files.walk(fixture)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    static Path createIdentities() {
        try {
            var directory = Files.createTempDirectory("spring-mtls-");
            var script = directory.resolve("init-mtls.py");
            Files.copy(Path.of("../../deploy/aws/monitoring/init-mtls.py"), script);
            execute(List.of("python3", script.toString()));
            var pki = directory.resolve("runtime/pki");
            // A valid client from our CA, but without the permitted Prometheus identity.
            execute(List.of("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes",
                    "-subj", "/CN=unapproved client", "-keyout", directory.resolve("unknown.key").toString(),
                    "-out", directory.resolve("unknown.csr").toString()));
            Files.writeString(directory.resolve("unknown.ext"), "extendedKeyUsage=clientAuth\n");
            execute(List.of("openssl", "x509", "-req", "-in", directory.resolve("unknown.csr").toString(),
                    "-CA", pki.resolve("ca/cert.pem").toString(), "-CAkey", pki.resolve("ca/key.pem").toString(),
                    "-set_serial", "123", "-days", "1", "-extfile", directory.resolve("unknown.ext").toString(),
                    "-out", directory.resolve("unknown.pem").toString()));
            for (var name : List.of("prometheus", "unknown")) {
                var cert = name.equals("prometheus") ? pki.resolve("prometheus/cert.pem") : directory.resolve("unknown.pem");
                var key = name.equals("prometheus") ? pki.resolve("prometheus/key.pem") : directory.resolve("unknown.key");
                execute(List.of("openssl", "pkcs12", "-export", "-in", cert.toString(), "-inkey", key.toString(),
                        "-certfile", pki.resolve("ca/cert.pem").toString(), "-passout", "pass:fixture",
                        "-out", directory.resolve(name + ".p12").toString()));
            }
            return directory;
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static void execute(List<String> command) throws Exception {
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("theeverthread.monitoring.enabled", () -> "true");
        registry.add("theeverthread.monitoring.port", () -> "0");
        registry.add("theeverthread.monitoring.certificate", () -> fixture.resolve("runtime/pki/app-node/cert.pem").toString());
        registry.add("theeverthread.monitoring.private-key", () -> fixture.resolve("runtime/pki/app-node/key.pem").toString());
        registry.add("theeverthread.monitoring.ca", () -> fixture.resolve("runtime/pki/app-node/ca.pem").toString());
    }

    record Response(int exit, String status, String body) {}

    Response request(String path, String identity, boolean verifyServer) throws Exception {
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        if (verifyServer) {
            try (var input = Files.newInputStream(fixture.resolve("runtime/pki/app-node/ca.pem"))) {
                trust.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(input));
            }
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        KeyManagerFactory keys = null;
        if (identity != null) {
            var store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(fixture.resolve(identity + ".p12"))) {
                store.load(input, "fixture".toCharArray());
            }
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, "fixture".toCharArray());
        }
        var context = SSLContext.getInstance("TLS");
        context.init(keys == null ? null : keys.getKeyManagers(), managers.getTrustManagers(), null);
        int port = metricsConnector.getLocalPort();
        try (var tcp = new java.net.Socket("127.0.0.1", port);
             var tls = (SSLSocket) context.getSocketFactory().createSocket(tcp, "172.31.45.83", port, true)) {
            tls.setSoTimeout(5000);
            var parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            tls.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 172.31.45.83\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            var response = new String(tls.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return new Response(0, response.split(" ", 3)[1], response);
        } catch (javax.net.ssl.SSLException | java.net.SocketException rejected) {
            return new Response(1, "000", rejected.toString());
        }
    }

    @Test
    void existingCaAndIdentitiesWorkWithSpringAndOtherRoutesStayDenied() throws Exception {
        var metrics = request("/actuator/prometheus", "prometheus", true);
        assertThat(metrics.exit()).as(metrics.body()).isZero();
        assertThat(metrics.status()).isEqualTo("200");
        assertThat(metrics.body()).contains("jvm_memory_used_bytes").doesNotContain("\"data\"");
        assertThat(request("/actuator/metrics", "prometheus", true).status()).isEqualTo("403");
        assertThat(request("/api/status", "prometheus", true).status()).isEqualTo("403");
        assertThat(request("/actuator/prometheus", "unknown", true).status()).isEqualTo("403");
    }

    @Test
    void missingClientIdentityAndUntrustedServerAreRejected() throws Exception {
        assertThat(request("/actuator/prometheus", null, true).exit()).isNotZero();
        assertThat(request("/actuator/prometheus", "prometheus", false).exit()).isNotZero();
    }

    @Test
    void existingHttpHealthProbesWorkAndHttpMetricsRemainDenied() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        for (var path : List.of("/actuator/health/readiness", "/actuator/health/liveness", "/api/status")) {
            var response = client.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + applicationPort + path)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
        }
        var response = client.send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + applicationPort + "/actuator/prometheus")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(401, 403);
    }
}
