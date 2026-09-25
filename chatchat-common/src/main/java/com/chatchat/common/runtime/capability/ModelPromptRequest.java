package com.chatchat.common.runtime.capability;

import com.chatchat.common.kernel.KernelDataScope;

/** Minimal model-compute input. Model routing stays in the established governed resolver. */
public record ModelPromptRequest(String modelName, String prompt, KernelDataScope scope) {
    public ModelPromptRequest {
        if (prompt == null || prompt.isBlank() || prompt.length() > 100_000)
            throw new IllegalArgumentException("model prompt must contain 1..100000 characters");
        if (scope == null) throw new IllegalArgumentException("model scope is required");
        modelName = modelName == null ? "" : modelName.trim();
    }
}
