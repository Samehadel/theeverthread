package com.theeverthread.knowledge.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Transformation levels accepted by the refinement HTTP contract. */
public enum RefinementMode {
    @JsonProperty("proofread")
    PROOFREAD,
    @JsonProperty("rephrase")
    REPHRASE,
    @JsonProperty("rewrite")
    REWRITE
}
