package com.chatchat.mcpserver.market;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.runtime.market.model.MarketObservation;
import com.chatchat.runtime.market.storage.FinancialDataIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/internal/v1/market")
public class MarketInternalController {
    private final FinancialDataIngestionService ingestion;
    private final Semaphore ingestionSlot = new Semaphore(1);

    public MarketInternalController(FinancialDataIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/observations")
    public ApiResponse<?> ingest(@RequestBody MarketObservation observation) {
        // Snapshot publication belongs to the serialized writer. Never let its
        // backlog occupy every HTTP thread needed by read and control requests.
        if (!ingestionSlot.tryAcquire())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Financial ingestion is busy; retry this observation later");
        try {
            var stored = ingestion.accept(observation);
            if (stored == null) throw new IllegalArgumentException("Market observation did not resolve to an enabled dataset");
            return ApiResponse.success(stored);
        } finally {
            ingestionSlot.release();
        }
    }
}
