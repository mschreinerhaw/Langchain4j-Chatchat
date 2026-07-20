package com.chatchat.mcpserver.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class NewsAdminControllerTest {
    @Test
    void keepsMcpAdminApiWhileProxyingToIndependentRuntime() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        NewsSourcePresetSeeder presetSeeder = mock(NewsSourcePresetSeeder.class);
        var rule = new ObjectMapper().readTree("{\"sourceId\":42}");
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("[]"));
        when(runtime.get("/sources/42/rule")).thenReturn(rule);
        when(runtime.get("/records?page=0&size=20")).thenReturn(new ObjectMapper().readTree("{\"items\":[],\"total\":0}"));
        when(runtime.post("/sources/42/robots-check", null)).thenReturn(new ObjectMapper().readTree(
            "{\"allowed\":true,\"status\":\"ALLOWED\",\"robotsUrl\":\"https://example.test/robots.txt\"}"));
        when(runtime.invoke(org.mockito.ArgumentMatchers.eq("news_search"), org.mockito.ArgumentMatchers.any()))
            .thenReturn(com.chatchat.common.tool.ToolOutput.success(java.util.Map.of("count", 0, "items", java.util.List.of())));

        var mvc = standaloneSetup(new NewsAdminController(runtime, mock(NewsSourcePresetCatalog.class),
            new NewsExtractionPatternCatalog(), new NewsCollectionTemplateCatalog(), presetSeeder)).build();
        mvc.perform(get("/api/v1/news/sources"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(presetSeeder).seedMissingPresets();

        mvc.perform(get("/api/v1/news/sources/42/rule"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceId").value(42));
        verify(runtime).get("/sources/42/rule");

        mvc.perform(post("/api/v1/news/sources/42/robots-check"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.allowed").value(true));
        verify(runtime).post("/sources/42/robots-check", null);

        mvc.perform(get("/api/v1/news/records"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(post("/api/v1/news/search").contentType("application/json").content("{\"query\":\"行情\",\"size\":10}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(true));

        mvc.perform(get("/api/v1/news/pattern-presets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].code").value("all_http_pages"))
            .andExpect(jsonPath("$.data[5].code").value("document_attachment"));
        mvc.perform(get("/api/v1/news/collection-templates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].code").value("disclosure_list_detail"))
            .andExpect(jsonPath("$.data[0].workflow").isArray());
    }
}
