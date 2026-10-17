package com.chatchat.api.runtime.coding.acceptance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Copies only published model connection rows to an offline, permission-restricted staging H2. */
public final class D1StagingModelCatalogCopy {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+):([^}]*)}");

    private D1StagingModelCatalogCopy() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Source YAML and staging H2 URL are required");
        Map<String, String> source = dataSource(Path.of(args[0]));
        try (Connection production = DriverManager.getConnection(source.get("url"),
                    source.get("username"), source.get("password"));
                Connection stage = DriverManager.getConnection(args[1], "sa", "")) {
            production.setReadOnly(true);
            stage.setAutoCommit(false);
            int count = 0;
            try (PreparedStatement query = production.prepareStatement("SELECT model_type,name,provider_model,"
                    + "base_url,protocol,dimension,timeout,max_tokens,max_retries,api_key_cipher,"
                    + "enabled,is_default,source,published_at FROM platform_model_publication "
                    + "WHERE model_type='chat' AND enabled=TRUE")) {
                try (ResultSet rows = query.executeQuery()) {
                    try (PreparedStatement clearPublications = stage.prepareStatement(
                            "DELETE FROM platform_model_publication WHERE model_type='chat'");
                            PreparedStatement clearConfigs = stage.prepareStatement(
                                "DELETE FROM platform_model_config WHERE model_type='chat'");
                            PreparedStatement insertPublication = stage.prepareStatement(
                                "INSERT INTO platform_model_publication (model_type,name,provider_model,"
                                    + "base_url,protocol,dimension,timeout,max_tokens,max_retries,api_key_cipher,"
                                    + "enabled,is_default,source,published_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                            PreparedStatement insertConfig = stage.prepareStatement(
                                "INSERT INTO platform_model_config (model_type,name,alias,description,provider_model,"
                                    + "base_url,protocol,dimension,timeout,max_tokens,max_retries,api_key_cipher,"
                                    + "enabled,is_default,source,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        clearPublications.executeUpdate();
                        clearConfigs.executeUpdate();
                        while (rows.next()) {
                            for (int column = 1; column <= 14; column++)
                                insertPublication.setObject(column, rows.getObject(column));
                            insertPublication.executeUpdate();
                            insertConfig.setObject(1, rows.getObject("model_type"));
                            insertConfig.setObject(2, rows.getObject("name"));
                            insertConfig.setNull(3, java.sql.Types.VARCHAR);
                            insertConfig.setNull(4, java.sql.Types.VARCHAR);
                            for (int column = 3; column <= 12; column++)
                                insertConfig.setObject(column + 2, rows.getObject(column));
                            insertConfig.setString(15, "stage");
                            insertConfig.setObject(16, rows.getObject("published_at"));
                            insertConfig.executeUpdate();
                            count++;
                        }
                    }
                }
            }
            if (count == 0) throw new IllegalStateException("No published chat model in production catalog");
            stage.commit();
            System.out.println("D1_STAGE published_chat_models_copied=" + count
                + " source_read_only=true stage_only=true");
        }
    }

    private static Map<String, String> dataSource(Path yaml) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (String line : Files.readAllLines(yaml)) {
            String value = line.trim();
            for (String key : new String[] {"url", "username", "password"}) {
                if (!value.startsWith(key + ":")) continue;
                value = value.substring(key.length() + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                    value = value.substring(1, value.length() - 1);
                Matcher placeholder = PLACEHOLDER.matcher(value);
                StringBuffer resolved = new StringBuffer();
                while (placeholder.find()) {
                    String selected = System.getenv(placeholder.group(1));
                    if (selected == null || selected.isBlank()) selected = placeholder.group(2);
                    placeholder.appendReplacement(resolved, Matcher.quoteReplacement(selected));
                }
                placeholder.appendTail(resolved);
                result.put(key, resolved.toString());
            }
        }
        if (!result.keySet().containsAll(java.util.Set.of("url", "username", "password")))
            throw new IllegalArgumentException("Source YAML lacks JDBC connection fields");
        return result;
    }
}
