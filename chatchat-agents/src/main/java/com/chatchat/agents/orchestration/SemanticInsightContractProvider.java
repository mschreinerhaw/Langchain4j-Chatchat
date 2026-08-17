package com.chatchat.agents.orchestration;

import java.util.List;

/**
 * Trusted source for tenant-scoped semantic formula contracts.
 * Runtime intentionally has no built-in formulas and fails closed when no provider is installed.
 */
public interface SemanticInsightContractProvider {

    Resolution resolve(Request request);

    static SemanticInsightContractProvider disabled() {
        return request -> new Resolution("skipped", "database_provider_unavailable", List.of());
    }

    record Request(
        String tenantId,
        String agentId,
        String taskType,
        String toolName,
        String datasetReference,
        boolean explicitlyRequested,
        List<String> requestedContractIds
    ) {
        public Request {
            requestedContractIds = requestedContractIds == null ? List.of() : List.copyOf(requestedContractIds);
        }
    }

    record Resolution(String status, String reason, List<SemanticInsightContract> contracts) {
        public Resolution {
            contracts = contracts == null ? List.of() : List.copyOf(contracts);
        }

        public boolean resolved() {
            return "resolved".equals(status) && !contracts.isEmpty();
        }
    }
}
