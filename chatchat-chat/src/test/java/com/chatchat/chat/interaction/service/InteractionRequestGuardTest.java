package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InteractionRequestGuardTest {

    private final ConversationContextProperties limits = new ConversationContextProperties();

    @Test
    void acceptsMultilingualEmojiAndBidirectionalUserTextWithinBudget() {
        String query = "中文 English العربية עברית हिन्दी 🚨👩🏽‍💻 \u202Eabc\u202C\nline two";
        assertThatCode(() -> InteractionRequestGuard.validate(
            InteractionRequest.builder().mode("agent_chat").query(query).build(), limits))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsOversizedInvalidUnicodeAndControlCharacterQueries() {
        limits.setMaxUserQueryChars(10);
        assertRejected(InteractionRequest.builder().query("x".repeat(11)).build(), "maximum length");
        assertRejected(InteractionRequest.builder().query("valid\u0000hidden").build(), "control characters");
        assertRejected(InteractionRequest.builder().query("broken\uD800").build(), "invalid Unicode");
        assertRejected(InteractionRequest.builder().query(" \n\t ").build(), "cannot be empty");
    }

    @Test
    void countsUnicodeCodePointsInsteadOfRejectingValidSurrogatePairsAtBoundary() {
        limits.setMaxUserQueryChars(4);
        assertThatCode(() -> InteractionRequestGuard.validate(
            InteractionRequest.builder().query("🚨🚨🚨🚨").build(), limits)).doesNotThrowAnyException();
        assertRejected(InteractionRequest.builder().query("🚨🚨🚨🚨🚨").build(), "maximum length");
    }

    @Test
    void rejectsNegativeRangesOversizedListsAndIdentifiers() {
        limits.setMaxAvailableTools(2);
        limits.setMaxImageAnalysisIds(1);
        limits.setMaxIdentifierChars(8);
        assertRejected(InteractionRequest.builder().query("ok").historyWindow(-1).build(), "historyWindow");
        assertRejected(InteractionRequest.builder().query("ok").maxResults(0).build(), "maxResults");
        assertRejected(InteractionRequest.builder().query("ok")
            .availableTools(List.of("a", "b", "c")).build(), "Available tools");
        List<String> toolsWithNull = new ArrayList<>();
        toolsWithNull.add(null);
        assertRejected(InteractionRequest.builder().query("ok")
            .availableTools(toolsWithNull).build(), "null items");
        assertRejected(InteractionRequest.builder().query("ok")
            .imageAnalysisIds(List.of("a", "b")).build(), "Image analysis ids");
        assertRejected(InteractionRequest.builder().query("ok")
            .conversationId("conversation-too-long").build(), "Conversation id");
    }

    @Test
    void rejectsDeepWideLargeAndCyclicToolInputWithoutRecursiveTraversal() {
        limits.setMaxToolInputDepth(5);
        limits.setMaxToolInputNodes(20);
        limits.setMaxToolInputTextChars(30);
        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int index = 0; index < 8; index++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cursor.put("n", next);
            cursor = next;
        }
        assertRejected(request(deep), "maximum depth");

        assertRejected(request(Map.of("items", new ArrayList<>(java.util.Collections.nCopies(25, 1)))),
            "maximum node count");
        var oversizedWithoutSafeIteration = new AbstractCollection<Object>() {
            @Override
            public Iterator<Object> iterator() {
                throw new AssertionError("oversized collection must be rejected before iteration");
            }

            @Override
            public int size() {
                return 1_000_000;
            }
        };
        assertRejected(request(Map.of("items", oversizedWithoutSafeIteration)), "maximum node count");
        assertRejected(request(Map.of("a", "x".repeat(20), "b", "y".repeat(20))), "text budget");

        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        assertRejected(request(cyclic), "maximum");
    }

    private InteractionRequest request(Map<String, Object> toolInput) {
        return InteractionRequest.builder().query("safe query").toolInput(toolInput).build();
    }

    private void assertRejected(InteractionRequest request, String message) {
        assertThatThrownBy(() -> InteractionRequestGuard.validate(request, limits))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(message);
    }
}
