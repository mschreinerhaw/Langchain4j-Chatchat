package com.chatchat.mcpserver.sql.datasource;

import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.chatchat.agents.protocol.ModelProtocolJson;

/** Native query drivers share the database asset's address and credentials. */
public final class NativeQueryDatasource {
    private static final Set<String> TYPES = Set.of("neo4j", "opensearch", "elasticsearch");
    private NativeQueryDatasource() {}

    public static String type(SqlDatasourceConfig asset) {
        String driver = asset.getDriverClass() == null ? "" : asset.getDriverClass().toLowerCase(Locale.ROOT);
        for (String type : TYPES) if ((type + "-http").equals(driver)) return type;
        String type = asset.getDatabaseType() == null ? "" : asset.getDatabaseType().toLowerCase(Locale.ROOT);
        String address = asset.getJdbcUrl() == null ? "" : asset.getJdbcUrl();
        return TYPES.contains(type) && (address.startsWith("http://") || address.startsWith("https://")) ? type : null;
    }

    public static boolean isNative(SqlDatasourceConfig asset) { return asset != null && type(asset) != null; }

    public static void normalize(SqlDatasourceConfig asset) {
        String type = type(asset);
        if (type == null) return;
        String selected = asset.getDatabaseType();
        if (selected != null && !selected.equals("generic") && !selected.equals(type))
            throw new IllegalArgumentException("数据库类型与原生查询驱动不匹配");
        String driver = asset.getDriverClass();
        if (driver != null && !driver.isBlank() && !driver.equals(type + "-http"))
            throw new IllegalArgumentException("HTTP 连接请选择对应的原生查询驱动");
        validateAddress(asset.getJdbcUrl());
        asset.setDatabaseType(type); asset.setDriverClass(type + "-http");
        asset.setMetadataAutoRefreshEnabled(false);
    }

    public static void validateAddress(String address) {
        URI uri;
        try { uri = URI.create(address); } catch (Exception ex) { throw new IllegalArgumentException("原生查询驱动需要 HTTP(S) 基础地址", ex); }
        if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme())) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("原生查询驱动需要 HTTP(S) 基础地址；账号和密码请在对应字段配置");
    }

    public static HttpEndpointConfig connection(SqlDatasourceConfig asset) {
        String type = type(asset);
        if (type == null) throw new IllegalArgumentException("资产未配置原生查询驱动");
        validateAddress(asset.getJdbcUrl());
        HttpEndpointConfig connection = new HttpEndpointConfig();
        connection.setId(asset.getId()); connection.setName(asset.getName()); connection.setEnabled(asset.isEnabled());
        connection.setCategory("neo4j".equals(type) ? "graph_database" : "search_engine");
        connection.setUrlTemplate(asset.getJdbcUrl()); connection.setMethod("POST");
        connection.setTimeoutMs(Math.max(1, asset.getDefaultTimeoutSeconds()) * 1000);
        if (asset.getUsername() != null && !asset.getUsername().isBlank()) {
            String credentials = asset.getUsername() + ":" + (asset.getPassword() == null ? "" : asset.getPassword());
            connection.setHeadersJson(ModelProtocolJson.compact(Map.of("Authorization", "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))));
        }
        return connection;
    }
}
