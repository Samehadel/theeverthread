package com.theeverthread.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.theeverthread.knowledge.api.exception.KnowledgeException;
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
    void transformationLevelsHaveDistinctBoundedBudgetsAndValidateBeforeUse() {
        assertThat(RefinementRules.mode(null)).isEqualTo("proofread");
        assertThatThrownBy(() -> RefinementRules.mode("invent"))
                .isInstanceOf(KnowledgeException.class);
        assertThat(RefinementRules.maxCharacters("bug", "title", "rephrase")).isEqualTo(23);
        assertThat(RefinementRules.maxCharacters("bug", "title", "rewrite")).isEqualTo(63);
        for (String mode : java.util.List.of("proofread", "rephrase", "rewrite")) {
            assertThat(RefinementRules.maxCharacters("x".repeat(200), "title", mode)).isEqualTo(200);
            assertThat(RefinementRules.maxCharacters("x".repeat(1000), "capture", mode)).isEqualTo(1000);
        }
        assertThat(RefinementRules.instruction("proofread")).contains("minimal");
        assertThat(RefinementRules.instruction("rephrase")).contains("Go beyond grammar");
        assertThat(RefinementRules.instruction("rewrite")).contains("change the whole sentence");
    }
}
