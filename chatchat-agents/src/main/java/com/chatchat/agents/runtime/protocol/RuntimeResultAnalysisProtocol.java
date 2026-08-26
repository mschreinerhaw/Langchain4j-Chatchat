package com.chatchat.agents.runtime.protocol;

import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;
import java.util.Map;

/** Converts arbitrary Runtime results into the canonical model-analysis projection. */
public interface RuntimeResultAnalysisProtocol extends RuntimeProtocolPort {

    String PROJECTION_SCHEMA_VERSION = AgentProtocolCatalog.RUNTIME_RESULT_ANALYSIS;

    Map<String, Object> analysisProjection(String datasetReference, Object boundedPayload);

    Map<String, Object> analysisProjection(String datasetReference,
                                           Object boundedPayload,
                                           int maximumRecordChars);

    Map<String, Object> protocolAnalysisProjection(String datasetReference,
                                                   Object boundedPayload,
                                                   int maximumRecordChars);
}
