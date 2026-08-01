package com.chatchat.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonRequestSizeFilterTest {

    @Test
    void rejectsDeclaredOversizedJsonBeforeControllerAndReportsEndpoint() throws Exception {
        JsonRequestSizeProperties properties = new JsonRequestSizeProperties();
        properties.setMaxBytes(65_536);
        JsonRequestSizeFilter filter = new JsonRequestSizeFilter(properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/tasks/t-1/feedback") {
            @Override public long getContentLengthLong() { return 20_054_016L; }
            @Override public int getContentLength() { return 20_054_016; }
        };
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("65536", "receivedBytes", "20054016");
    }

    @Test
    void boundsStreamingJsonEvenWithoutContentLength() {
        JsonRequestSizeProperties properties = new JsonRequestSizeProperties();
        properties.setMaxBytes(65_536);
        JsonRequestSizeFilter filter = new JsonRequestSizeFilter(properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat") {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
        };
        request.setContentType("application/json");
        request.setContent(("{\"query\":\"" + "x".repeat(80_000) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
            (req, res) -> req.getInputStream().readAllBytes()))
            .isInstanceOf(JsonRequestSizeFilter.RequestBodyTooLargeException.class)
            .hasMessageContaining("65536");
    }
}
