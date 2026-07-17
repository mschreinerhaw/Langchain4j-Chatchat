package com.chatchat.mcpserver.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class NewsAdminControllerTest {
    @Test
    void keepsMcpAdminApiWhileProxyingToIndependentRuntime() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        var rule = new ObjectMapper().readTree("{\"sourceId\":42}");
        when(runtime.get("/sources/42/rule")).thenReturn(rule);

        var mvc = standaloneSetup(new NewsAdminController(runtime, mock(NewsSourcePresetCatalog.class),
            new NewsExtractionPatternCatalog())).build();
        mvc.perform(get("/api/v1/news/sources/42/rule"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceId").value(42));
        verify(runtime).get("/sources/42/rule");

        mvc.perform(get("/api/v1/news/pattern-presets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].code").value("all_http_pages"))
            .andExpect(jsonPath("$.data[5].code").value("document_attachment"));
    }
}
