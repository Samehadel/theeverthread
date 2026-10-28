package com.theeverthread.identity.internal.service;

public interface VerificationMail {
    String send(String email, Long id, String token);
}
