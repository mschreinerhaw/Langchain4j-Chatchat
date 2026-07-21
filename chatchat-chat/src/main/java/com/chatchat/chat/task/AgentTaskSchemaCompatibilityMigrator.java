package com.chatchat.chat.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/** Applies small, idempotent schema upgrades required by persisted Agent answers. */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentTaskSchemaCompatibilityMigrator implements ApplicationRunner {

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String database = connection.getMetaData().getDatabaseProductName();
            String normalizedDatabase = database == null ? "" : database.toLowerCase(Locale.ROOT);
            if (!normalizedDatabase.contains("mysql") && !normalizedDatabase.contains("mariadb")) {
                return;
            }
            widenToLongText(connection, "agent_task_latest", "answer_summary");
            widenToLongText(connection, "agent_task_latest", "final_notification_json");
            widenToLongText(connection, "agent_experience", "answer_summary");
        }
    }

    private void widenToLongText(Connection connection, String table, String column) throws Exception {
        String currentType = columnType(connection, table, column);
        if (currentType == null || "longtext".equalsIgnoreCase(currentType)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE `" + table + "` MODIFY COLUMN `" + column + "` LONGTEXT NULL");
        }
        log.info("agent_task_schema_compatibility_applied table={} column={} from={} to=LONGTEXT",
            table, column, currentType);
    }

    private String columnType(Connection connection, String table, String column) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(
            connection.getCatalog(), null, table, column)) {
            return columns.next() ? columns.getString("TYPE_NAME") : null;
        }
    }
}
