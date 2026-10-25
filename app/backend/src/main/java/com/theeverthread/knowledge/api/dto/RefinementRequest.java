package com.theeverthread.knowledge.api.dto;

/** Only selected Title or Capture wording is sent for refinement. */
public record RefinementRequest(String field, TicketContent content, String mode) {
    /** Preserve the conservative behavior of clients that omit mode. */
    public RefinementRequest(String field, TicketContent content) {
        this(field, content, null);
    }
}
