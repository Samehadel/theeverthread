package com.theeverthread.knowledge.internal.service;

import com.theeverthread.knowledge.api.dto.*;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/** Sends content-only JSON, never entities, author information, or workspace metadata. */
@RequiredArgsConstructor
public class SpringAiKnowledgeAi implements KnowledgeAi {
    private static final Resource TITLE_PROMPT =
            new ClassPathResource("prompts/knowledge/refine-title-system.st");
    private static final Resource CAPTURE_PROMPT =
            new ClassPathResource("prompts/knowledge/refine-capture-system.st");
    private static final Resource CLASSIFICATION_PROMPT =
            new ClassPathResource("prompts/knowledge/classify-system.st");
    private final ChatClient knowledgeChatClient;
    private final JsonMapper json;

    public record Classification(List<String> attributes) {}

    public record Wording(String text) {}

    @Override
    public String refine(String original, String field, RefinementMode mode) {
        Resource prompt = "title".equals(field) ? TITLE_PROMPT : CAPTURE_PROMPT;
        var result =
                knowledgeChatClient
                        .prompt()
                        .system(
                                system ->
                                        system.text(prompt)
                                                .param("inputLength", original.length())
                                                .param(
                                                        "transformation",
                                                        RefinementRules.instruction(mode))
                                                .param(
                                                        "maxCharacters",
                                                        RefinementRules.maxCharacters(
                                                                original, field, mode)))
                        .user(json.writeValueAsString(Map.of("text", original)))
                        .call()
                        .entity(Wording.class, spec -> spec.useProviderStructuredOutput());
        if (result == null) throw new IllegalArgumentException("Missing refinement");
        return result.text();
    }

    @Override
    public List<String> classify(TicketContent content, Map<String, String> definitions) {
        var result =
                knowledgeChatClient
                        .prompt()
                        .system(
                                system ->
                                        system.text(CLASSIFICATION_PROMPT)
                                                .param(
                                                        "catalogVersion",
                                                        AttributeCatalog.version(definitions))
                                                .param(
                                                        "maxSelections",
                                                        AttributeCatalog.MAX_SELECTIONS)
                                                .param(
                                                        "catalog",
                                                        json.writeValueAsString(definitions)))
                        .user(json.writeValueAsString(content))
                        .call()
                        .entity(Classification.class, spec -> spec.useProviderStructuredOutput());
        if (result == null) throw new IllegalArgumentException("Missing classification");
        return AttributeCatalog.validate(result.attributes(), definitions);
    }
}
