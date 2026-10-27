package com.theeverthread.knowledge.api.service;

import com.theeverthread.knowledge.api.dto.*;

import java.util.Map;

public interface KnowledgeService {
    Map<String, String> attributes(String email, Long workspaceId);

    TicketView create(String email, Long workspaceId, CreateTicketRequest request);

    TicketPage browse(
            String email, Long workspaceId, String detailsType, Integer limit, String cursor);

    TicketView get(String email, Long workspaceId, Long id);

    RefinementView refine(String email, Long workspaceId, RefinementRequest request);
}
