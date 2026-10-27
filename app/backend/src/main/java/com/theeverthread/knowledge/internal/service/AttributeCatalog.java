package com.theeverthread.knowledge.internal.service;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reads the shared catalog on demand so updates do not require a backend restart. */
@Service
@RequiredArgsConstructor
public class AttributeCatalog {
    public static final int MAX_SELECTIONS = 6;
    private final JdbcTemplate jdbc;

    public Map<String, String> definitions() {
        return read("DESCRIPTION", true);
    }

    /** Inactive entries retain their display labels for already classified tickets. */
    public Map<String, String> labels() {
        return read("DISPLAY_LABEL", false);
    }

    private Map<String, String> read(String column, boolean activeOnly) {
        var values = new LinkedHashMap<String, String>();
        jdbc.query(
                        "SELECT ID, "
                                + column
                                + " FROM KNOWLEDGE_ATTRIBUTE"
                                + (activeOnly ? " WHERE ACTIVE" : "")
                                + " ORDER BY SORT_ORDER, ID",
                        (row, index) -> Map.entry(row.getString(1), row.getString(2)))
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(values);
    }

    public static List<String> validate(List<String> selections, Map<String, String> definitions) {
        if (selections == null
                || selections.stream().anyMatch(s -> s == null || !definitions.containsKey(s))) {
            throw new IllegalArgumentException("Invalid classification selections");
        }
        var distinct = selections.stream().distinct().toList();
        if (distinct.size() > MAX_SELECTIONS) {
            throw new IllegalArgumentException("Too many classification selections");
        }
        return distinct;
    }

    /** Identifies the exact active definitions used by the prompt, independent of map order. */
    public static String version(Map<String, String> definitions) {
        try {
            var canonical = JsonMapper.shared().writeValueAsBytes(new TreeMap<>(definitions));
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return "v3:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
