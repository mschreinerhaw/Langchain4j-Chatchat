package com.chatchat.agents.orchestration.model;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/** Numeric request shape only. Embedded business sections and provider token attribution are unknown. */
public final class ModelRequestContext {
    private ModelRequestContext() {}

    static Map<String, Object> flattened(String prompt) {
        var counts = counts("FLATTENED");
        counts.put("unattributedChars", chars(prompt));
        return counts;
    }

    public static Map<String, Object> structured(ChatRequest request) {
        try {
            return measureStructured(request);
        } catch (RuntimeException measurementFailure) {
            // Measurement is observational. Unsupported request contents cannot block dispatch.
            return counts("UNAVAILABLE");
        }
    }

    private static Map<String, Object> measureStructured(ChatRequest request) {
        if (request == null) return counts("UNAVAILABLE");
        var counts = counts("STRUCTURED");
        var messages = request.messages();
        int latestUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                latestUser = i;
                break;
            }
        }

        long system = 0, user = 0, history = 0, toolResults = 0, otherParts = 0;
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            if (message instanceof SystemMessage systemMessage) {
                system += chars(systemMessage.text());
            } else if (message instanceof UserMessage userMessage) {
                long text = 0;
                for (var part : userMessage.contents()) {
                    if (part instanceof TextContent content) text += chars(content.text());
                    else otherParts++;
                }
                if (i == latestUser) user += text;
                else history += text;
            } else if (message instanceof AiMessage assistant) {
                history += chars(assistant.text());
                if (assistant.hasToolExecutionRequests()) {
                    for (var call : assistant.toolExecutionRequests())
                        history += chars(call.name()) + chars(call.arguments());
                }
            } else if (message instanceof ToolExecutionResultMessage result) {
                toolResults += chars(result.text());
            } else {
                otherParts++;
            }
        }
        counts.put("systemChars", system);
        counts.put("userChars", user);
        counts.put("historyChars", history);
        counts.put("toolsChars", request.toolSpecifications().stream().mapToLong(tool -> tool.toJson().length()).sum());
        counts.put("toolResultChars", toolResults);
        counts.put("nonTextParts", otherParts);
        return counts;
    }

    private static Map<String, Object> counts(String format) {
        var counts = new LinkedHashMap<String, Object>();
        counts.put("format", format);
        for (String field : new String[]{"systemChars", "userChars", "historyChars", "toolsChars",
                "toolResultChars", "skillsChars", "evidenceChars", "unattributedChars", "nonTextParts"}) {
            counts.put(field, null);
        }
        return counts;
    }

    private static int chars(String value) {
        return value == null ? 0 : value.length();
    }
}
