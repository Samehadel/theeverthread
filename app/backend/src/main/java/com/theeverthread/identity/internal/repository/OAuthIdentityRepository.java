package com.theeverthread.identity.internal.repository;

import com.theeverthread.identity.internal.entity.OAuthIdentityEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentityEntity, Long> {
    Optional<OAuthIdentityEntity> findByProviderAndSubjectId(String provider, String subjectId);
}
