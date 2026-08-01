package com.chatchat.e2e;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.AgentRuntimeProperties;
import com.chatchat.agents.runtime.NoopAgentRunEventPublisher;
import com.chatchat.agents.runtime.RocksDbAgentRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionUpgradeRollbackCompatibilityE2E {

    @TempDir
    Path tempDir;

    @Test
    void candidateUpgradeAndRollbackKeepTerminalStateUniqueAndDoNotReplaySideEffects() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setRocksDbPath(tempDir.resolve("upgrade-rollback-store").toString());

        RocksDbAgentRunStore versionN = store(properties);
        versionN.open();
        versionN.start(request("completed-before-upgrade"));
        versionN.complete("completed-before-upgrade", AgentRunResult.builder()
            .runId("completed-before-upgrade")
            .status(AgentRunStatus.COMPLETED)
            .answer("tool side effect committed once")
            .build());
        versionN.start(request("active-during-upgrade"));
        versionN.start(request("confirmation-during-upgrade"));
        versionN.complete("confirmation-during-upgrade", AgentRunResult.builder()
            .runId("confirmation-during-upgrade")
            .confirmationRequired(true)
            .status(AgentRunStatus.WAITING_CONFIRMATION)
            .build());
        versionN.close();

        RocksDbAgentRunStore versionNPlusOne = store(properties);
        versionNPlusOne.open();
        assertThat(versionNPlusOne.find("completed-before-upgrade").orElseThrow().status())
            .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(versionNPlusOne.find("active-during-upgrade").orElseThrow().status())
            .isEqualTo(AgentRunStatus.FAILED);
        assertThat(versionNPlusOne.find("confirmation-during-upgrade").orElseThrow().status())
            .isEqualTo(AgentRunStatus.WAITING_CONFIRMATION);
        assertThat(versionNPlusOne.events("completed-before-upgrade"))
            .filteredOn(event -> event.type().name().equals("RUN_COMPLETED"))
            .hasSize(1);
        versionNPlusOne.close();

        RocksDbAgentRunStore rolledBackVersionN = store(properties);
        rolledBackVersionN.open();
        assertThat(rolledBackVersionN.events("completed-before-upgrade"))
            .filteredOn(event -> event.type().name().equals("RUN_COMPLETED"))
            .hasSize(1);
        assertThat(rolledBackVersionN.events("active-during-upgrade"))
            .filteredOn(event -> event.type().name().equals("RUN_FAILED"))
            .hasSize(1);
        assertThat(rolledBackVersionN.find("confirmation-during-upgrade").orElseThrow().status())
            .isEqualTo(AgentRunStatus.WAITING_CONFIRMATION);
        rolledBackVersionN.close();
    }

    private RocksDbAgentRunStore store(AgentRuntimeProperties properties) {
        return new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(), properties, new ObjectMapper());
    }

    private AgentRunRequest request(String runId) {
        return AgentRunRequest.builder()
            .runId(runId)
            .requestId("request-" + runId)
            .tenantId("tenant-upgrade")
            .userId("release-test")
            .query("upgrade compatibility")
            .build();
    }
}
