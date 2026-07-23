package com.chatchat.runtime.news.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyAwareHttpClientFactoryTest {
    @Test
    void prefersHttpsProxyAndParsesItsAddress() {
        Map<String, String> environment = Map.of(
            "HTTP_PROXY", "http://fallback.example:8080",
            "HTTPS_PROXY", "http://proxy.example:18081");

        var address = ProxyAwareHttpClientFactory.proxyAddress(environment::get);

        assertThat(address.getHostString()).isEqualTo("proxy.example");
        assertThat(address.getPort()).isEqualTo(18081);
    }

    @Test
    void ignoresInvalidProxyConfiguration() {
        var address = ProxyAwareHttpClientFactory.proxyAddress(
            key -> "HTTPS_PROXY".equals(key) ? "not a valid uri" : null);

        assertThat(address).isNull();
    }
}
