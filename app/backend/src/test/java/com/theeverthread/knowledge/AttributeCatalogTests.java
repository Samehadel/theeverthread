package com.theeverthread.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.theeverthread.knowledge.internal.service.AttributeCatalog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class AttributeCatalogTests {
    private final Map<String, String> definitions =
            Map.of("type:decision", "A choice", "area:family", "Family", "area:career", "Career");

    @Test
    void validatesAgainstTheSuppliedSnapshotAndAllowsAbstention() {
        assertThat(
                        AttributeCatalog.validate(
                                List.of(
                                        "type:decision",
                                        "area:family",
                                        "area:career",
                                        "area:family"),
                                definitions))
                .containsExactly("type:decision", "area:family", "area:career");
        assertThat(AttributeCatalog.validate(List.of(), definitions)).isEmpty();
        assertThat(
                        AttributeCatalog.validate(
                                List.of("area:gardening"), Map.of("area:gardening", "Gardens")))
                .containsExactly("area:gardening");
    }

    @Test
    void rejectsUnknownNullAndExcessiveSelections() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AttributeCatalog.validate(null, definitions));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AttributeCatalog.validate(List.of("area:invented"), definitions));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                AttributeCatalog.validate(
                                        java.util.Arrays.asList((String) null), definitions));
        var many = new LinkedHashMap<String, String>();
        for (int i = 0; i <= AttributeCatalog.MAX_SELECTIONS; i++)
            many.put("area:test-" + i, "Test topic");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AttributeCatalog.validate(List.copyOf(many.keySet()), many));
    }

    @Test
    void fingerprintsDefinitionsIndependentOfOrderAndTracksContentChanges() {
        var reversed = new LinkedHashMap<String, String>();
        definitions.keySet().stream()
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(id -> reversed.put(id, definitions.get(id)));
        assertThat(AttributeCatalog.version(reversed))
                .isEqualTo(AttributeCatalog.version(definitions));
        reversed.put("area:family", "Updated family definition");
        assertThat(AttributeCatalog.version(reversed))
                .isNotEqualTo(AttributeCatalog.version(definitions));
        assertThat(AttributeCatalog.version(definitions)).hasSize(67);
    }
}
