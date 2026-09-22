package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionRequest;

import java.util.List;
import java.util.Set;

/** Database-authorized MCP candidates ordered by a relevance index. */
public interface McpToolCandidateRetriever {
    Selection retrieve(InteractionRequest request, List<String> candidateNames, int limit);

    record Selection(Set<String> managedNames, Set<String> allowedNames, List<String> rankedNames) {
        public Selection {
            managedNames = managedNames == null ? Set.of() : Set.copyOf(managedNames);
            allowedNames = allowedNames == null ? Set.of() : Set.copyOf(allowedNames);
            rankedNames = rankedNames == null ? List.of() : List.copyOf(rankedNames);
        }
    }
}
