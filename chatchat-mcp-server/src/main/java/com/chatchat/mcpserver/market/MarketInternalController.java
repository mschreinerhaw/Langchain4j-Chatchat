package com.chatchat.mcpserver.market;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.runtime.market.model.MarketObservation;
import com.chatchat.runtime.market.storage.FinancialDataIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/market")
public class MarketInternalController {
    private final FinancialDataIngestionService ingestion;

    public MarketInternalController(FinancialDataIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/observations")
    public ApiResponse<?> ingest(@RequestBody MarketObservation observation) {
        var stored = ingestion.accept(observation);
        if (stored == null) throw new IllegalArgumentException("Market observation did not resolve to an enabled dataset");
        return ApiResponse.success(stored);
    }
}
