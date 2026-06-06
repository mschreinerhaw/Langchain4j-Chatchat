package com.chatchat.api.application.interaction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified enterprise response model for all interaction modes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InteractionResponse {
    private String conversationId;
    private String requestId;
    private String mode;
    private String answer;

    @Builder.Default
    private List<InteractionSource> sources = new ArrayList<>();

    @Builder.Default
    private List<InteractionToolTrace> toolTraces = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    private Long latencyMs;
    private Long timestamp;
}

