package com.chatchat.runtime.news.http;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Locale;
import java.util.function.Function;

/**
 * Creates HTTP clients that honor the conventional proxy environment variables
 * used by container and server deployments.
 */
public final class ProxyAwareHttpClientFactory {
    private ProxyAwareHttpClientFactory() {
    }

    public static HttpClient.Builder builder() {
        HttpClient.Builder builder = HttpClient.newBuilder();
        InetSocketAddress proxy = proxyAddress(System::getenv);
        if (proxy != null) builder.proxy(ProxySelector.of(proxy));
        return builder;
    }

    static InetSocketAddress proxyAddress(Function<String, String> environment) {
        String value = first(environment.apply("HTTPS_PROXY"), environment.apply("https_proxy"),
            environment.apply("HTTP_PROXY"), environment.apply("http_proxy"));
        if (value == null) return null;
        try {
            URI uri = URI.create(value.contains("://") ? value : "http://" + value);
            if (uri.getHost() == null) return null;
            int port = uri.getPort();
            if (port < 0) port = "https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) ? 443 : 80;
            return InetSocketAddress.createUnresolved(uri.getHost(), port);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
