package com.chatchat.api.controller;

import com.chatchat.api.application.interaction.model.InteractionMode;
import com.chatchat.api.application.interaction.model.InteractionRequest;
import com.chatchat.api.application.interaction.model.InteractionResponse;
import com.chatchat.api.application.interaction.service.InteractionOrchestrationService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unified interaction API that aligns with ChatChat-style multi-mode workflows.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/interactions")
@Tag(name = "Interactions", description = "Unified enterprise interaction APIs")
public class InteractionController {

    private final InteractionOrchestrationService orchestrationService;

    @PostMapping("/chat")
    @Operation(summary = "Unified chat endpoint with mode-based orchestration")
    public ApiResponse<InteractionResponse> chat(@RequestBody InteractionRequest request) {
        try {
            InteractionResponse response = orchestrationService.chat(request);
            return ApiResponse.success(response, "Interaction completed");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Interaction failed: " + e.getMessage());
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Unified chat endpoint with SSE progressive response")
    public SseEmitter streamChat(@RequestBody InteractionRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event()
                    .name("start")
                    .data(Map.of("timestamp", System.currentTimeMillis())));

                InteractionResponse response = orchestrationService.chat(request);
                emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(Map.of(
                        "conversationId", nullToEmpty(response.getConversationId()),
                        "requestId", nullToEmpty(response.getRequestId()),
                        "mode", nullToEmpty(response.getMode()),
                        "timestamp", response.getTimestamp() == null ? System.currentTimeMillis() : response.getTimestamp(),
                        "latencyMs", response.getLatencyMs() == null ? 0 : response.getLatencyMs(),
                        "sources", response.getSources() == null ? List.of() : response.getSources(),
                        "toolTraces", response.getToolTraces() == null ? List.of() : response.getToolTraces()
                    )));

                for (String chunk : splitAnswer(response.getAnswer())) {
                    emitter.send(SseEmitter.event()
                        .name("delta")
                        .data(Map.of("content", chunk)));
                }

                emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("timestamp", System.currentTimeMillis())));
                emitter.complete();
            } catch (IllegalArgumentException e) {
                sendErrorEvent(emitter, e.getMessage());
            } catch (Exception e) {
                sendErrorEvent(emitter, "Interaction failed: " + e.getMessage());
            } finally {
                executor.shutdown();
            }
        });
        return emitter;
    }

    @GetMapping("/modes")
    @Operation(summary = "List supported interaction modes")
    public ApiResponse<List<ModeDefinition>> listModes() {
        List<ModeDefinition> modes = Arrays.stream(InteractionMode.values())
            .map(mode -> new ModeDefinition(mode.code(), describe(mode)))
            .toList();
        return ApiResponse.success(modes);
    }

    private String describe(InteractionMode mode) {
        return switch (mode) {
            case LLM_CHAT -> "General LLM conversation with short-term memory";
            case KNOWLEDGE_BASE_CHAT -> "RAG conversation against managed knowledge bases";
            case AGENT_CHAT -> "Agent loop with dynamic tool orchestration";
            case TOOL_DIRECT -> "Direct tool invocation without agent planning";
        };
    }

    public record ModeDefinition(String mode, String description) {
    }

    private List<String> splitAnswer(String answer) {
        String value = answer == null || answer.isBlank() ? "No response generated" : answer;
        int chunkSize = 2;
        int[] codePoints = value.codePoints().toArray();
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < codePoints.length; index += chunkSize) {
            int end = Math.min(index + chunkSize, codePoints.length);
            chunks.add(new String(codePoints, index, end - index));
        }
        return chunks;
    }

    private void sendErrorEvent(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                .name("error")
                .data(Map.of("message", message == null ? "Interaction failed" : message)));
        } catch (Exception ignored) {
            // The connection may already be closed.
        } finally {
            emitter.complete();
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
