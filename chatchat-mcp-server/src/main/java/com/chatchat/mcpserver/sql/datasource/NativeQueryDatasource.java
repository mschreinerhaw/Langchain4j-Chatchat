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
        for (String type : TYPES) if ((type + "-http").equals(driver) || (type + "-native").equals(driver)) return type;
        String type = asset.getDatabaseType() == null ? "" : asset.getDatabaseType().toLowerCase(Locale.ROOT);
        return TYPES.contains(type) ? type : null;
    }

    public static boolean isNative(SqlDatasourceConfig asset) { return asset != null && type(asset) != null; }

    public static void normalize(SqlDatasourceConfig asset) {
        String type = type(asset);
        if (type == null) return;
        String selected = asset.getDatabaseType();
        if (selected != null && !selected.equals("generic") && !selected.equals(type))
            throw new IllegalArgumentException("数据库类型与原生查询驱动不匹配");
        String driver = asset.getDriverClass();
        if (driver != null && !driver.isBlank() && !driver.equals(type + "-http") && !driver.equals(type + "-native"))
            throw new IllegalArgumentException("请选择对应的内置原生客户端驱动");
        validateAddress(type, asset.getJdbcUrl());
        asset.setDatabaseType(type); asset.setDriverClass(type + "-native");
    }

    public static void validateAddress(String address) {
        validateAddress("opensearch", address);
    }

    public static void validateAddress(String type, String address) {
        if ("neo4j".equals(type)) {
            URI uri;
            try { uri = URI.create(address); } catch (Exception ex) { throw new IllegalArgumentException("Neo4j requires a Bolt/neo4j URI", ex); }
            if (!Set.of("bolt", "bolt+s", "bolt+ssc", "neo4j", "neo4j+s", "neo4j+ssc").contains(String.valueOf(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")))
                throw new IllegalArgumentException("Neo4j requires a Bolt/neo4j URI (e.g. bolt://host:7687), not an HTTP endpoint; configure credentials separately");
            return;
        }
        URI uri;
        try { uri = URI.create(address); } catch (Exception ex) { throw new IllegalArgumentException("原生查询驱动需要 HTTP(S) 基础地址", ex); }
        if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme())) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("原生查询驱动需要 HTTP(S) 基础地址；账号和密码请在对应字段配置");
    }

    public static HttpEndpointConfig connection(SqlDatasourceConfig asset) {
        String type = type(asset);
        if (type == null) throw new IllegalArgumentException("资产未配置原生查询驱动");
        validateAddress(type, asset.getJdbcUrl());
        HttpEndpointConfig connection = new HttpEndpointConfig();
        connection.setId(asset.getId()); connection.setName(asset.getName()); connection.setEnabled(asset.isEnabled());
        connection.setCategory(type);
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
