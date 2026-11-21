package com.theeverthread.foundation.internal.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;

/** A metrics-only TLS connector that leaves the application and health probes on HTTP. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "theeverthread.monitoring.enabled", havingValue = "true")
class MonitoringConfiguration {
    @Bean
    Connector metricsConnector(
            @Value("${theeverthread.monitoring.port:8443}") int port,
            @Value("${theeverthread.monitoring.certificate:/run/secrets/metrics_certificate}") String certificate,
            @Value("${theeverthread.monitoring.private-key:/run/secrets/metrics_key}") String privateKey,
            @Value("${theeverthread.monitoring.ca:/run/secrets/metrics_ca}") String ca) throws Exception {
        if (port != 0 && port < 1024) {
            throw new IllegalArgumentException("Metrics requires its own unprivileged port");
        }
        var connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("address", "0.0.0.0");
        connector.setProperty("SSLEnabled", "true");
        var tls = new SSLHostConfig();
        tls.setProtocols("TLSv1.2,+TLSv1.3");
        tls.setCertificateVerification("required");
        // JSSE needs a Java trust store, not OpenSSL's CA-file setting.
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        try (var input = Files.newInputStream(Path.of(ca))) {
            trust.setCertificateEntry("monitoring-ca", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        tls.setTrustStore(trust);
        var identity = new SSLHostConfigCertificate(tls, SSLHostConfigCertificate.Type.RSA);
        identity.setCertificateFile(certificate);
        identity.setCertificateKeyFile(privateKey);
        tls.addCertificate(identity);
        connector.addSslHostConfig(tls);
        return connector;
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> metricsListener(Connector metricsConnector) {
        return factory -> factory.addAdditionalConnectors(metricsConnector);
    }

    @Bean
    @Order(1)
    SecurityFilterChain metricsSecurity(HttpSecurity http, Connector metricsConnector) throws Exception {
        return http
                .securityMatcher(request -> request.getLocalPort() == metricsConnector.getLocalPort())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.disable())
                .x509(x509 -> x509.userDetailsService(name -> {
                    if (!"theeverthread prometheus".equals(name)) {
                        throw new UsernameNotFoundException("Unknown monitoring identity");
                    }
                    return User.withUsername(name).password("").roles("MONITORING").build();
                }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").hasRole("MONITORING")
                        .anyRequest().denyAll())
                .build();
    }
}
