package com.theeverthread.knowledge.internal.service;

import com.theeverthread.knowledge.api.dto.RefinementMode;
import com.theeverthread.knowledge.api.dto.TicketContent;

/** Controls how freely existing wording can change while retaining its meaning. */
public final class RefinementRules {
    private RefinementRules() {}

    public static String original(TicketContent content, String field) {
        String value = "title".equals(field) ? content.title() : content.capture();
        if (!TicketRules.meaningful(value))
            TicketRules.invalid("Enter wording in the selected field before refining.");
        return value.strip();
    }

    /** Compatibility budget for clients that omit a transformation mode. */
    public static int maxCharacters(String original, String field) {
        return maxCharacters(original, field, RefinementMode.PROOFREAD);
    }

    public static String instruction(RefinementMode mode) {
        return switch (mode) {
            case PROOFREAD -> "Proofread only. Fix spelling, grammar, and punctuation with minimal"
                    + " changes. Retain word choice and sentence structure wherever possible."
                    + " Return already-correct wording unchanged.";
            case REPHRASE -> "Rephrase for clarity and flow. Improve word choice, remove redundancy,"
                    + " and reorganize awkward phrasing. Go beyond grammar when clearer wording is"
                    + " possible, while staying close to the original expression.";
            case REWRITE -> "Rewrite substantially. Rethink word choice, sentence structure, and"
                    + " organization to express the same idea more clearly, meaningfully, and"
                    + " directly. Replace weak or awkward phrasing with a stronger formulation."
                    + " You may change the whole sentence; do not merely proofread. Stay faithful"
                    + " to the original idea without inventing context or resolving ambiguity by guessing.";
        };
    }

    public static int maxCharacters(String original, String field, RefinementMode mode) {
        int allowance = switch (mode) {
            case PROOFREAD -> Math.max(4, (original.length() + 4) / 5);
            case REPHRASE -> Math.max(20, (original.length() + 1) / 2);
            case REWRITE -> Math.max(60, original.length());
        };
        return Math.min("title".equals(field) ? 200 : 1000, original.length() + allowance);
    }
}
