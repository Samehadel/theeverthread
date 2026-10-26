package com.theeverthread.knowledge.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

public record TicketView(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
        TicketContent content,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Long authorId,
        String authorName,
        Instant createdAt,
        String classificationStatus,
        List<String> attributes) {}
