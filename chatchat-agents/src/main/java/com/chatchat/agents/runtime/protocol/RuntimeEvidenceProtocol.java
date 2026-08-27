package com.chatchat.agents.runtime.protocol;

import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

/** Captures an untrusted tool return inside the Runtime-owned evidence boundary. */
public interface RuntimeEvidenceProtocol<E extends RuntimeEvidenceEnvelope>
    extends RuntimeProtocolPort {

    String EVIDENCE_SCHEMA_VERSION = AgentProtocolCatalog.RUNTIME_EVIDENCE;

    E capture(ToolRuntimeRequest request, String toolName, String outcome, Object boundedPayload);

    GovernanceIsolationScope trustedScope(ToolRuntimeRequest request);
}
