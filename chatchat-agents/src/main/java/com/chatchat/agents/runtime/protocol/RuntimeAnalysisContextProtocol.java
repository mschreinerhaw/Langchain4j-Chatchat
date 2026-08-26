package com.chatchat.agents.runtime.protocol;

import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.Map;

/** Builds source-neutral analysis context from tool metadata and returned protocol metadata. */
public interface RuntimeAnalysisContextProtocol extends RuntimeProtocolPort {

    String CONTEXT_SCHEMA_VERSION = AgentProtocolCatalog.RUNTIME_ANALYSIS_CONTEXT;

    Map<String, Object> adapt(String reference, ToolMetadata metadata, Object output);

    Map<String, Object> adaptDataset(Map<String, Object> rootContext,
                                     Map<String, Object> dataset);
}
