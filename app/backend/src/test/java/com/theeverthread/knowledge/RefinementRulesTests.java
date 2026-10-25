package com.theeverthread.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.theeverthread.knowledge.api.dto.RefinementMode;

import com.theeverthread.knowledge.internal.service.RefinementRules;

import org.junit.jupiter.api.Test;

class RefinementRulesTests {
    @Test
    void lengthBudgetTracksOriginalInputAndRetainsFieldCaps() {
        assertThat(RefinementRules.maxCharacters("bug", "title")).isEqualTo(7);
        assertThat(RefinementRules.maxCharacters("x".repeat(100), "capture")).isEqualTo(120);
        assertThat(RefinementRules.maxCharacters("x".repeat(200), "title")).isEqualTo(200);
        assertThat(RefinementRules.maxCharacters("x".repeat(1000), "capture")).isEqualTo(1000);
    }

    @Test
    void transformationLevelsHaveDistinctBoundedBudgets() {
        assertThat(RefinementRules.maxCharacters("bug", "title", RefinementMode.REPHRASE)).isEqualTo(23);
        assertThat(RefinementRules.maxCharacters("bug", "title", RefinementMode.REWRITE)).isEqualTo(63);
        for (RefinementMode mode : RefinementMode.values()) {
            assertThat(RefinementRules.maxCharacters("x".repeat(200), "title", mode)).isEqualTo(200);
            assertThat(RefinementRules.maxCharacters("x".repeat(1000), "capture", mode)).isEqualTo(1000);
        }
        assertThat(RefinementRules.instruction(RefinementMode.PROOFREAD)).contains("minimal");
        assertThat(RefinementRules.instruction(RefinementMode.REPHRASE)).contains("Go beyond grammar");
        assertThat(RefinementRules.instruction(RefinementMode.REWRITE)).contains("change the whole sentence");
    }
}
