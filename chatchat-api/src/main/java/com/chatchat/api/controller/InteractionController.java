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
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

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
}

