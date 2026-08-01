package com.chatchat.mcpserver.market;

import com.chatchat.runtime.market.storage.FinancialDataStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;

class SecurityMasterPaginationReleaseTest {

    @Test
    void repeatedRemotePageStopsPaginationEvenWhenProviderClaimsMillionsOfPages() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger requests = new AtomicInteger();
        SecurityMasterImportService service = new SecurityMasterImportService(mock(FinancialDataStore.class), mapper) {
            @Override
            protected JsonNode getJson(String url, String referer) {
                requests.incrementAndGet();
                return response(mapper, 1_000_000, "000001");
            }
        };
        var target = new LinkedHashMap<String, FinancialDataStore.SecurityMasterRecord>();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
            service.fetchSzseTab("tab1", "agdm", "agjc", "agssrq", "STOCK", target));

        assertThat(requests).hasValue(2);
        assertThat(target).containsKey("000001");
    }

    private static JsonNode response(ObjectMapper mapper, int pageCount, String code) {
        ObjectNode metadata = mapper.createObjectNode().put("tabkey", "tab1").put("pagecount", pageCount);
        ArrayNode rows = mapper.createArrayNode().add(mapper.createObjectNode()
            .put("agdm", code).put("agjc", "Sample").put("agssrq", "20200101"));
        ObjectNode section = mapper.createObjectNode().set("metadata", metadata);
        section.set("data", rows);
        return mapper.createArrayNode().add(section);
    }
}
