package com.chatchat.agents.model;

import java.net.URI;
import java.util.Locale;

/** Resolves configured model URLs into a wire protocol and a usable endpoint. */
public record ModelEndpoint(Protocol protocol, String url, boolean multimodal) {

    public enum Protocol {
        OPENAI,
        DASHSCOPE_NATIVE
    }

    public static ModelEndpoint resolve(String configuredUrl, String configuredProtocol) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw new IllegalArgumentException("Model base URL must not be blank");
        }
        String url = configuredUrl.trim().replaceAll("/+$", "");
        URI.create(url);
        String protocol = configuredProtocol == null
            ? "auto"
            : configuredProtocol.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        boolean nativePath = isDashScopeNativePath(url);
        if ("dashscope-multimodal".equals(protocol)) {
            return new ModelEndpoint(Protocol.DASHSCOPE_NATIVE, url, true);
        }
        if ("dashscope-text".equals(protocol)) {
            return new ModelEndpoint(Protocol.DASHSCOPE_NATIVE, url, false);
        }
        if ("dashscope-native".equals(protocol) || "dashscope".equals(protocol)
            || ("auto".equals(protocol) && nativePath)) {
            return new ModelEndpoint(Protocol.DASHSCOPE_NATIVE, url,
                url.toLowerCase(Locale.ROOT).contains("/multimodal-generation/"));
        }
        if (!"auto".equals(protocol) && !"openai".equals(protocol)
            && !"openai-compatible".equals(protocol)) {
            throw new IllegalArgumentException("Unsupported model protocol: " + configuredProtocol);
        }
        return new ModelEndpoint(Protocol.OPENAI, openAiBaseUrl(url), false);
    }

    private static boolean isDashScopeNativePath(String url) {
        String normalized = url.toLowerCase(Locale.ROOT);
        return normalized.contains("/api/v1/services/aigc/multimodal-generation/generation")
            || normalized.contains("/api/v1/services/aigc/text-generation/generation");
    }

    private static String openAiBaseUrl(String url) {
        String normalized = url.toLowerCase(Locale.ROOT);
        for (String suffix : new String[] {"/chat/completions", "/responses"}) {
            if (normalized.endsWith(suffix)) {
                return url.substring(0, url.length() - suffix.length());
            }
        }
        return url;
    }
}
