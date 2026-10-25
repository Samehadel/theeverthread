package com.theeverthread.knowledge.api.dto;

/** Only selected Title or Capture wording is sent for refinement. */
public record RefinementRequest(String field, TicketContent content, RefinementMode mode) {
    public RefinementRequest {
        if (mode == null) mode = RefinementMode.PROOFREAD;
    }

    /** Preserve the conservative behavior of clients that omit mode. */
    public RefinementRequest(String field, TicketContent content) {
        this(field, content, null);
    }
}
