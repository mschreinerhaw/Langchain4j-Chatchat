package com.chatchat.mcpserver.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NewsSourcePresetSeederTest {
    @Test
    void seedsMissingPresetsThroughRuntimeClient() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("[]"));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));

        new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog()).seedMissingPresets();

        verify(runtime, times(8)).post(eq("/sources"), any());
        verify(runtime, times(4)).put(contains("/rule"), any());
    }
}
