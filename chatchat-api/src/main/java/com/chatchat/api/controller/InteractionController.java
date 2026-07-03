package com.chatchat.api.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * Performs the chat operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/chat")
    @Operation(summary = "Unified chat endpoint with mode-based orchestration")
    public ApiResponse<InteractionResponse> chat(@RequestBody InteractionRequest request,
                                                 HttpServletRequest servletRequest) {
        try {
            bindRequestTenant(request, servletRequest);
            InteractionResponse response = orchestrationService.chat(request);
            return ApiResponse.success(response, "Interaction completed");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Interaction failed: " + e.getMessage());
        }
    }

    /**
     * Performs the stream chat operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Unified chat endpoint with SSE progressive response")
    public SseEmitter streamChat(@RequestBody InteractionRequest request,
                                 HttpServletRequest servletRequest) {
        bindRequestTenant(request, servletRequest);
        SseEmitter emitter = new SseEmitter(0L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event()
                    .name("start")
                    .data(Map.of("timestamp", System.currentTimeMillis())));

                InteractionResponse response = orchestrationService.chat(request);
                List<?> toolTraces = safeToolTraces(response);
                emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(Map.of(
                        "conversationId", nullToEmpty(response.getConversationId()),
                        "requestId", nullToEmpty(response.getRequestId()),
                        "mode", nullToEmpty(response.getMode()),
                        "timestamp", response.getTimestamp() == null ? System.currentTimeMillis() : response.getTimestamp(),
                        "latencyMs", response.getLatencyMs() == null ? 0 : response.getLatencyMs(),
                        "sources", response.getSources() == null ? List.of() : response.getSources(),
                        "toolTraces", toolTraces
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

    /**
     * Lists the modes.
     *
     * @return the modes list
     */
    @GetMapping("/modes")
    @Operation(summary = "List supported interaction modes")
    public ApiResponse<List<ModeDefinition>> listModes() {
        List<ModeDefinition> modes = Arrays.stream(InteractionMode.values())
            .map(mode -> new ModeDefinition(mode.code(), describe(mode)))
            .toList();
        return ApiResponse.success(modes);
    }

    /**
     * Performs the describe operation.
     *
     * @param mode the mode value
     * @return the operation result
     */
    private String describe(InteractionMode mode) {
        return switch (mode) {
            case LLM_CHAT -> "General LLM conversation with short-term memory";
            case AGENT_CHAT -> "Agent loop with dynamic tool orchestration";
            case TOOL_DIRECT -> "Direct tool invocation without agent planning";
        };
    }

    public record ModeDefinition(String mode, String description) {
    }

    /**
     * Performs the safe tool traces operation.
     *
     * @param response the response value
     * @return the operation result
     */
    private List<?> safeToolTraces(InteractionResponse response) {
        if (response == null || response.getToolTraces() == null) {
            return List.of();
        }
        return response.getToolTraces();
    }

    /**
     * Performs the split answer operation.
     *
     * @param answer the answer value
     * @return the operation result
     */
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

    /**
     * Sends the error event.
     *
     * @param emitter the emitter value
     * @param message the message value
     */
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

    /**
     * Performs the null to empty operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void bindRequestTenant(InteractionRequest request, HttpServletRequest servletRequest) {
        if (request == null) {
            return;
        }
        String currentTenantId = requestAttribute(servletRequest, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        if (currentTenantId != null && !currentTenantId.isBlank()) {
            request.setTenantId(currentTenantId.trim());
        } else if (request.getTenantId() == null || request.getTenantId().isBlank()) {
            request.setTenantId("default");
        }
    }

    private String requestAttribute(HttpServletRequest request, String name) {
        Object value = request == null ? null : request.getAttribute(name);
        return value == null ? null : String.valueOf(value);
    }
}
