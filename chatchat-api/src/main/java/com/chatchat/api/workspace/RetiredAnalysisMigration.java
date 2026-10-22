package com.chatchat.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;

/** Retires product metadata once, retaining exact original rows for an explicit rollback. */
@Component @RequiredArgsConstructor
public class RetiredAnalysisMigration {
    private static final String MARKER = "professional-agents-v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @EventListener(ApplicationReadyEvent.class) @Order(140) @Transactional
    public void migrate() throws Exception {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS runtime_product_retirement_archive (
              batch_id varchar(64) not null, record_type varchar(64) not null, record_id varchar(128) not null,
              saved_payload text not null, replacement_payload text, created_at timestamp not null default CURRENT_TIMESTAMP,
              primary key(batch_id,record_type,record_id))
            """);
        boolean postgres;
        try (var connection = jdbc.getDataSource().getConnection()) {
            postgres = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
            if (postgres)
                jdbc.execute("select pg_advisory_xact_lock(hashtext('retire-professional-agents-v1'))");
        }
        if (!jdbc.queryForList("select batch_id from runtime_product_retirement_archive where record_type='MARKER' and record_id=?", MARKER).isEmpty()) return;
        if (jdbc.queryForObject("select count(*) from sys_menu where parent_id in (select id from sys_menu where menu_code='domainAnalysis') and menu_code<>'domainAnalysis'", Integer.class) > 0)
            throw new IllegalStateException("Retired analysis menu has custom children; reparent them before migration");
        String batch = UUID.randomUUID().toString();
        archiveRows(batch, "sys_permission", "permission_code in ('workspace:analysis:api','system:agent-registry:manage')", postgres);
        archiveRows(batch, "sys_menu", "menu_code='domainAnalysis'", postgres);
        archiveRows(batch, "sys_role_permission", "permission_id in (select id from sys_permission where permission_code='workspace:analysis:api')", postgres);
        archiveRows(batch, "sys_menu_permission", "menu_id in (select id from sys_menu where menu_code='domainAnalysis') or permission_id in (select id from sys_permission where permission_code='workspace:analysis:api')", postgres);
        for (var row : jdbc.queryForList("select t.*" + (postgres ? ",row_to_json(t)::text as retirement_saved_row" : "") + " from workspace_registry t")) {
            String original = String.valueOf(row.get("manifest_json"));
            var manifest = (ObjectNode) json.readTree(original);
            var pages = json.createArrayNode();
            for (var page : manifest.path("pages")) if (!"domainAnalysis".equals(page.path("componentKey").asText())) pages.add(page);
            if (pages.size() == manifest.path("pages").size()) continue;
            manifest.set("pages", pages);
            if ("domainAnalysis".equals(manifest.path("entryPage").asText()))
                manifest.put("entryPage", pages.isEmpty() ? "" : pages.get(0).path("id").asText());
            manifest.put("configVersion", manifest.path("configVersion").asInt() + 1);
            String replacement = json.writeValueAsString(manifest);
            archive(batch, "workspace_registry", String.valueOf(row.get("id")), postgres ? row.get("retirement_saved_row") : row, replacement);
            jdbc.update("update workspace_registry set manifest_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id=?", replacement, row.get("id"));
        }
        jdbc.update("delete from sys_menu_permission where menu_id in (select id from sys_menu where menu_code='domainAnalysis') or permission_id in (select id from sys_permission where permission_code='workspace:analysis:api')");
        jdbc.update("delete from sys_role_permission where permission_id in (select id from sys_permission where permission_code='workspace:analysis:api')");
        jdbc.update("delete from sys_menu where menu_code='domainAnalysis'");
        jdbc.update("delete from sys_permission where permission_code='workspace:analysis:api'");
        jdbc.update("update sys_permission set permission_name=?,resource_path=? where permission_code='system:agent-registry:manage'",
            "Runtime 运行配置管理", "/api/v1/enterprise/runtime-adapters/**");
        archive(batch, "MARKER", MARKER, Map.of("version", 1), null);
    }
    private void archiveRows(String batch, String table, String condition, boolean postgres) throws Exception {
        for (var row : jdbc.queryForList("select t.*" + (postgres ? ",row_to_json(t)::text as retirement_saved_row" : "") + " from " + table + " t where " + condition))
            archive(batch, table, String.valueOf(row.get("id")), postgres ? row.get("retirement_saved_row") : row, null);
    }
    private void archive(String batch, String type, String id, Object row, String replacement) throws Exception {
        String saved = row instanceof String raw ? raw : json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(row);
        jdbc.update("insert into runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload,replacement_payload) values(?,?,?,?,?)", batch, type, id, saved, replacement);
    }
}
