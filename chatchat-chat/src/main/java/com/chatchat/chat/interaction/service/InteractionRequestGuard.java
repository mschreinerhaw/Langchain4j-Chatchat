package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionRequest;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Map;

/** Applies bounded, model-independent budgets before persistence or runtime execution. */
final class InteractionRequestGuard {

    private InteractionRequestGuard() {
    }

    static void validate(InteractionRequest request, ConversationContextProperties limits) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        String query = request.getQuery();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }
        validateText("Query", query, positive(limits.getMaxUserQueryChars(), 64_000), false);
        validateText("System prompt", request.getSystemPrompt(),
            positive(limits.getMaxSystemPromptChars(), 32_000), true);

        int identifierLimit = positive(limits.getMaxIdentifierChars(), 256);
        validateText("Conversation id", request.getConversationId(), identifierLimit, true);
        validateText("Tenant id", request.getTenantId(), identifierLimit, true);
        validateText("User id", request.getUserId(), identifierLimit, true);
        validateText("Mode", request.getMode(), identifierLimit, true);
        validateText("Model name", request.getModelName(), identifierLimit, true);
        validateText("Skill id", request.getSkillId(), identifierLimit, true);
        validateText("Tool name", request.getToolName(), identifierLimit, true);

        validateStringCollection("Available tools", request.getAvailableTools(),
            positive(limits.getMaxAvailableTools(), 256), identifierLimit);
        validateStringCollection("Image analysis ids", request.getImageAnalysisIds(),
            positive(limits.getMaxImageAnalysisIds(), 64), identifierLimit);
        validateRange("historyWindow", request.getHistoryWindow(), 0,
            positive(limits.getMaxHistoryWindow(), 100));
        validateRange("maxResults", request.getMaxResults(), 1,
            positive(limits.getMaxResults(), 1_000));
        validateToolInput(request.getToolInput(), limits, identifierLimit);
    }

    private static void validateText(String field, String value, int maximumChars, boolean blankAllowed) {
        if (value == null) return;
        if (!blankAllowed && value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be empty");
        }
        int codePoints = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(field + " contains invalid Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(field + " contains invalid Unicode");
            } else if (Character.isISOControl(current) && current != '\n' && current != '\r' && current != '\t') {
                throw new IllegalArgumentException(field + " contains unsupported control characters");
            }
            if (++codePoints > maximumChars) {
                throw new IllegalArgumentException(
                    field + " exceeds maximum length of " + maximumChars + " characters");
            }
        }
    }

    private static void validateStringCollection(String field, Collection<String> values,
                                                 int maximumItems, int maximumItemChars) {
        if (values == null) return;
        if (values.size() > maximumItems) {
            throw new IllegalArgumentException(field + " exceeds maximum item count of " + maximumItems);
        }
        for (String value : values) {
            if (value == null) {
                throw new IllegalArgumentException(field + " cannot contain null items");
            }
            validateText(field + " item", value, maximumItemChars, false);
        }
    }

    private static void validateRange(String field, Integer value, int minimum, int maximum) {
        if (value != null && (value < minimum || value > maximum)) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void validateToolInput(Map<String, Object> input,
                                          ConversationContextProperties limits,
                                          int maximumKeyChars) {
        if (input == null || input.isEmpty()) return;
        int maximumDepth = positive(limits.getMaxToolInputDepth(), 20);
        int maximumNodes = positive(limits.getMaxToolInputNodes(), 10_000);
        int maximumTextChars = positive(limits.getMaxToolInputTextChars(), 256_000);
        long nodes = 0;
        long textChars = 0;
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.add(new Node(input, 1));
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (++nodes > maximumNodes) {
                throw new IllegalArgumentException("Tool input exceeds maximum node count of " + maximumNodes);
            }
            if (node.depth() > maximumDepth) {
                throw new IllegalArgumentException("Tool input exceeds maximum depth of " + maximumDepth);
            }
            Object value = node.value();
            if (value instanceof Map<?, ?> map) {
                rejectOversizedExpansion(nodes, pending.size(), map.size(), maximumNodes);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    validateText("Tool input key", key, maximumKeyChars, false);
                    textChars += key.codePointCount(0, key.length());
                    pending.addLast(new Node(entry.getValue(), node.depth() + 1));
                }
            } else if (value instanceof Collection<?> collection) {
                rejectOversizedExpansion(nodes, pending.size(), collection.size(), maximumNodes);
                for (Object item : collection) pending.addLast(new Node(item, node.depth() + 1));
            } else if (value != null && value.getClass().isArray()) {
                int length = Array.getLength(value);
                rejectOversizedExpansion(nodes, pending.size(), length, maximumNodes);
                for (int index = 0; index < length; index++) {
                    pending.addLast(new Node(Array.get(value, index), node.depth() + 1));
                }
            } else if (value instanceof CharSequence sequence) {
                String text = sequence.toString();
                validateText("Tool input text", text, maximumTextChars, true);
                textChars += text.codePointCount(0, text.length());
            }
            if (textChars > maximumTextChars) {
                throw new IllegalArgumentException(
                    "Tool input exceeds maximum text budget of " + maximumTextChars + " characters");
            }
        }
    }

    private static void rejectOversizedExpansion(long visitedNodes, int pendingNodes,
                                                  int addedNodes, int maximumNodes) {
        if (visitedNodes + pendingNodes + (long) addedNodes > maximumNodes) {
            throw new IllegalArgumentException("Tool input exceeds maximum node count of " + maximumNodes);
        }
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private record Node(Object value, int depth) {
    }
}
