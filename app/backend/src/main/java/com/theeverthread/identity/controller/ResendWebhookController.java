package com.theeverthread.identity.controller;

import com.theeverthread.identity.internal.service.ResendWebhook;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ResendWebhookController {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final ResendWebhook webhook;

    @PostMapping("/api/webhooks/resend")
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        log.info("Receive webhook resend request");
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(413).build();
        }
        boolean verified =
                webhook.receive(
                        request.getHeader("svix-id"),
                        request.getHeader("svix-timestamp"),
                        request.getHeader("svix-signature"),
                        body);
        return verified ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
    }
}
