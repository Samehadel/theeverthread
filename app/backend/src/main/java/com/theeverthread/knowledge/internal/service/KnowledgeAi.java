package com.theeverthread.knowledge.internal.service;

import com.theeverthread.knowledge.api.dto.*;

import java.util.List;

public interface KnowledgeAi {
    default String refine(String original, String field) {
        return refine(original, field, "proofread");
    }

    String refine(String original, String field, String mode);

    List<String> classify(TicketContent content);
}
