package com.chatchat.mcpserver.datacapability.connection;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** Explicit endpoint-scoped trust for privately issued search certificates. */
@Data
@Component
@ConfigurationProperties(prefix = "chatchat.mcp.native-search.tls")
public class NativeSearchTlsProperties {
    private List<Endpoint> endpoints = new ArrayList<>();

    @Data
    public static class Endpoint {
        private String url;
        private String caCertificateFile;
        // Optional certificate DNS identity when the connection address is an IP or proxy.
        private String serverName;
    }
}
