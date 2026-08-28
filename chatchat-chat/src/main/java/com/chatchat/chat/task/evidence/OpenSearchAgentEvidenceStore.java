package com.chatchat.chat.task.evidence;

import com.chatchat.common.runtime.evidence.EvidencePayloadStorePort;
import com.chatchat.chat.conversation.store.ChatMessageTextStore;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Stores agent evidence payloads in the configured OpenSearch text index.
 */
@Component
public class OpenSearchAgentEvidenceStore implements EvidencePayloadStorePort {

    private final ChatMessageTextStore textStore;

    public OpenSearchAgentEvidenceStore(ChatMessageTextStore textStore) {
        this.textStore = textStore;
    }

    @Override
    public boolean isEnabled() {
        return textStore.isEnabled();
    }

    @Override
    public void put(String documentId,
                    String tenantId,
                    String runId,
                    String evidenceId,
                    String json) {
        textStore.putText(documentId, "agent_runtime_evidence", tenantId, runId, evidenceId, json);
    }

    @Override
    public Optional<String> get(String documentId) {
        return textStore.getText(documentId);
    }

    @Override
    public void delete(String documentId) {
        textStore.delete(documentId);
    }
}
