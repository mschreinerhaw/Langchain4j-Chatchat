package com.chatchat.agents.runtime;

import com.chatchat.common.tool.ToolMetadata;

public interface ToolRuntimePolicyProvider {

    /**
     * Resolves the resolve.
     *
     * @param request the request value
     * @param metadata the metadata value
     * @return the resolved resolve
     */
    ToolRuntimePolicy resolve(ToolRuntimeRequest request, ToolMetadata metadata);
}
