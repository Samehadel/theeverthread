package com.theeverthread.identity.internal.service;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "email.provider", havingValue = "resend")
class ResendNotifications {
    private final DeliveryProvider provider;
    private final EmailLedger ledger;

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<Void> invalidSignature() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @PostMapping("/api/webhooks/resend")
    ResponseEntity<Void> receive(@RequestBody byte[] body, @RequestHeader HttpHeaders headers) {
        ledger.observe(provider.verify(body, headers));
        return ResponseEntity.noContent().build();
    }
}
