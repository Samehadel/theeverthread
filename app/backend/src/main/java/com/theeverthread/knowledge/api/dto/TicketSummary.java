package com.theeverthread.knowledge.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

public record TicketSummary(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
        String title,
        String capturePreview,
        String detailsType,
        String authorName,
        Instant createdAt,
        String classificationStatus,
        List<String> attributes) {}
