package com.chatchat.chat.task.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

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
            if (normalizedDatabase.contains("mysql") || normalizedDatabase.contains("mariadb")) {
                widenToLongText(connection, "agent_task_latest", "answer_summary");
                widenToLongText(connection, "agent_task_latest", "final_notification_json");
                widenToLongText(connection, "agent_experience", "answer_summary");
            }
            backfillExecutionIdentity(connection);
            backfillEventScope(connection);
        }
    }

    private void backfillExecutionIdentity(Connection connection) throws Exception {
        if (columnType(connection, "agent_task_latest", "execution_id") == null) {
            return;
        }
        String select = "select task_id, status from agent_task_latest where execution_id is null "
            + "or root_execution_id is null or execution_attempt_id is null or canonical_state is null";
        String update = "update agent_task_latest set execution_id=?, root_execution_id=?, "
            + "execution_attempt_id=?, execution_attempt_number=?, canonical_state=? where task_id=?";
        int migrated = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(select);
             PreparedStatement changes = connection.prepareStatement(update)) {
            while (rows.next()) {
                String taskId = rows.getString("task_id");
                String attemptId = "att-1-" + UUID.nameUUIDFromBytes(
                    ("legacy:" + taskId).getBytes(StandardCharsets.UTF_8));
                changes.setString(1, taskId);
                changes.setString(2, taskId);
                changes.setString(3, attemptId);
                changes.setInt(4, 1);
                changes.setString(5, canonicalState(rows.getString("status")));
                changes.setString(6, taskId);
                changes.addBatch();
                migrated++;
            }
            if (migrated > 0) changes.executeBatch();
        }
        if (migrated > 0) {
            log.info("agent_execution_identity_backfilled count={}", migrated);
        }
    }

    private void backfillEventScope(Connection connection) throws Exception {
        if (columnType(connection, "agent_execution_event", "event_scope") == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            int updated = statement.executeUpdate(
                "update agent_execution_event set event_scope='TASK' where event_scope is null");
            if (updated > 0) log.info("agent_execution_event_scope_backfilled count={}", updated);
        }
    }

    private String canonicalState(String status) {
        try {
            return AgentExecutionState.fromWire(status).name();
        } catch (IllegalArgumentException ignored) {
            return AgentExecutionState.FAILED.name();
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
