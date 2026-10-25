package com.theeverthread.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.*;

public record InvitationLinkRequest(
        @NotNull @Positive @JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {}
