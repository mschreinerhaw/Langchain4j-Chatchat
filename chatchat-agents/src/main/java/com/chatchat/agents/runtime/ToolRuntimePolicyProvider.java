package com.chatchat.agents.runtime;

import com.chatchat.common.tool.ToolMetadata;

public interface ToolRuntimePolicyProvider {

    ToolRuntimePolicy resolve(ToolRuntimeRequest request, ToolMetadata metadata);
}
