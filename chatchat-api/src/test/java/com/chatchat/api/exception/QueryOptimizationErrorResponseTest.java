package com.chatchat.api.exception;

import com.chatchat.common.retrieval.query.QueryOptimizationException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class QueryOptimizationErrorResponseTest {
    @Test void budgetRejectionIsAnExplicitDiagnosticRatherThanAnEmptySuccessfulSearch() {
        var response = new GlobalExceptionHandler().handleQueryOptimizationException(new QueryOptimizationException(
            "QUERY_BATCH_BUDGET_EXCEEDED", "Provide fewer independent intents", Map.of("maximumBatches", 8)));
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().getData()).containsEntry("errorCode", "QUERY_BATCH_BUDGET_EXCEEDED");
    }
    @Test void timeoutAndPartialResultsHaveDifferentPublicStatuses() {
        var handler = new GlobalExceptionHandler();
        assertThat(handler.handleQueryOptimizationException(new QueryOptimizationException("QUERY_TIMEOUT", "timeout")).getStatusCode().value()).isEqualTo(504);
        assertThat(handler.handleQueryOptimizationException(new QueryOptimizationException("QUERY_PARTIAL_BATCHES", "partial")).getStatusCode().value()).isEqualTo(502);
    }
}
