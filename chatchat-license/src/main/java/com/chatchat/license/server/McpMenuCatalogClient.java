package com.chatchat.license.server;

import com.chatchat.license.LicenseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.util.ArrayList;
import java.util.List;

/** Loads the installable menu modules from the target MCP service instead of maintaining a second list. */
@Service
public class McpMenuCatalogClient {
    private final LicenseCenterProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public McpMenuCatalogClient(LicenseCenterProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, client(properties));
    }

    McpMenuCatalogClient(LicenseCenterProperties properties, ObjectMapper objectMapper, RestClient restClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    public List<MenuModule> load() {
        String url = properties.getMcpMenuCatalogUrl();
        if (url == null || url.isBlank()) throw new LicenseException("未配置 MCP 菜单目录地址");
        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            JsonNode data = objectMapper.readTree(body).path("data");
            if (!data.isArray()) throw new LicenseException("MCP 菜单目录响应格式无效");
            List<MenuModule> menus = new ArrayList<>();
            for (JsonNode item : data) {
                String key = item.path("key").asText("").trim();
                String label = item.path("label").asText("").trim();
                String icon = item.path("icon").asText("").trim();
                boolean navigation = item.path("navigation").asBoolean(true);
                String parentKey = item.path("parentKey").asText("").trim();
                String description = item.path("description").asText("").trim();
                if (!key.isEmpty() && !label.isEmpty()) {
                    menus.add(new MenuModule(key, label, icon, navigation, parentKey, description));
                }
            }
            if (menus.isEmpty()) throw new LicenseException("MCP 服务未发布可授权菜单");
            return List.copyOf(menus);
        } catch (LicenseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LicenseException("同步 MCP 菜单目录失败: " + ex.getMessage(), ex);
        }
    }

    public record MenuModule(String key, String label, String icon, boolean navigation,
                             String parentKey, String description) {
        public MenuModule(String key, String label, String icon) {
            this(key, label, icon, true, "", "");
        }
    }

    private static RestClient client(LicenseCenterProperties properties) {
        int timeout = Math.max(100, properties.getMcpMenuCatalogTimeoutMs());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).build();
    }
}
