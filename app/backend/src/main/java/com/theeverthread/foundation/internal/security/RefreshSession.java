package com.theeverthread.foundation.internal.security;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "REFRESH_SESSION")
class RefreshSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false)
    String email;

    @Column(nullable = false, length = 64)
    String tokenHash;

    @Column(length = 64)
    String previousHash;

    @Column(nullable = false, length = 64)
    String credentialHash;

    @Column(nullable = false)
    Instant expiresAt;
}
