package com.theeverthread.knowledge.internal.service;

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
        return maxCharacters(original, field, "proofread");
    }

    public static String mode(String mode) {
        if (mode == null) return "proofread";
        if (!java.util.Set.of("proofread", "rephrase", "rewrite").contains(mode))
            TicketRules.invalid("Choose Proofread, Rephrase, or Rewrite for refinement.");
        return mode;
    }

    public static String instruction(String mode) {
        return switch (mode(mode)) {
            case "proofread" -> "Proofread only. Fix spelling, grammar, and punctuation with minimal"
                    + " changes. Retain word choice and sentence structure wherever possible."
                    + " Return already-correct wording unchanged.";
            case "rephrase" -> "Rephrase for clarity and flow. Improve word choice, remove redundancy,"
                    + " and reorganize awkward phrasing. Go beyond grammar when clearer wording is"
                    + " possible, while staying close to the original expression.";
            case "rewrite" -> "Rewrite substantially. Rethink word choice, sentence structure, and"
                    + " organization to express the same idea more clearly, meaningfully, and"
                    + " directly. Replace weak or awkward phrasing with a stronger formulation."
                    + " You may change the whole sentence; do not merely proofread. Stay faithful"
                    + " to the original idea without inventing context or resolving ambiguity by guessing.";
            default -> throw new IllegalArgumentException("Unsupported refinement mode");
        };
    }

    public static int maxCharacters(String original, String field, String mode) {
        int allowance = switch (mode(mode)) {
            case "proofread" -> Math.max(4, (original.length() + 4) / 5);
            case "rephrase" -> Math.max(20, (original.length() + 1) / 2);
            case "rewrite" -> Math.max(60, original.length());
            default -> throw new IllegalArgumentException("Unsupported refinement mode");
        };
        return Math.min("title".equals(field) ? 200 : 1000, original.length() + allowance);
    }
}
