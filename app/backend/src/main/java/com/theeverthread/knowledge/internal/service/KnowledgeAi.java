package com.theeverthread.knowledge.internal.service;

import com.theeverthread.knowledge.api.dto.*;

import java.util.List;

public interface KnowledgeAi {
    default String refine(String original, String field) {
        return refine(original, field, RefinementMode.PROOFREAD);
    }

    String refine(String original, String field, RefinementMode mode);

    List<String> classify(TicketContent content);
}
