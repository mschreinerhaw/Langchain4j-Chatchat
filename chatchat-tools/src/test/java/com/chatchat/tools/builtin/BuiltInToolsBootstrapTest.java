package com.chatchat.tools.builtin;

import com.chatchat.agents.evidence.sql.SqlState;

import com.chatchat.agents.tool.DefaultToolRegistry;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BuiltInToolsBootstrapTest {

    @Test
    void builtInToolsExposeGovernanceMetadataWithoutLegacyWebSearch() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );

        bootstrap.initializeBuiltInTools();

        ToolMetadata calculator = registry.getToolMetadata("calculator");
        assertThat(calculator.getCategory()).isEqualTo("utility_calculation");
        assertThat(calculator.getRiskLevel()).isEqualTo("low");
        assertThat(calculator.getOperationType()).isEqualTo("read");
        assertThat(calculator.getConfirmation()).containsEntry("default", "auto_execute");
        assertThat(calculator.getInputPolicy()).containsEntry("must_show_parameters", true);

        assertThat(registry.hasTool("web_search")).isFalse();

        ToolMetadata databaseQuery = registry.getToolMetadata("database_query");
        assertThat(databaseQuery.getCategory()).isEqualTo("database_data_query");
        assertThat(databaseQuery.getRiskLevel()).isEqualTo("high");
        assertThat(databaseQuery.getOperationType()).isEqualTo("read");
        assertThat(databaseQuery.isUserVisible()).isFalse();
        assertThat(databaseQuery.isAgentCompatible()).isFalse();
        assertThat(databaseQuery.getConfirmation()).containsEntry("default", "ask_before_execute");
        assertThat(databaseQuery.getInputPolicy()).containsEntry("allow_auto_fill", false);
        assertThat(databaseQuery.getOutputPolicy()).containsKey("mask_fields");
        assertThat(databaseQuery.getParameters()).extracting("name")
            .contains("jdbc_url", "driver_class", "database_type", "reload_drivers");

        ToolMetadata fileSystem = registry.getToolMetadata("file_system");
        assertThat(fileSystem.getCategory()).isEqualTo("local_file_system");
        assertThat(fileSystem.getRiskLevel()).isEqualTo("high");
        assertThat(fileSystem.getOperationType()).isEqualTo("read");
        assertThat(fileSystem.getConfirmation()).containsEntry("default", "ask_before_execute");
        assertThat(fileSystem.getInputPolicy()).containsEntry("allow_auto_fill", false);
        assertThat(fileSystem.getOutputPolicy()).containsKey("mask_fields");
    }

    @Test
    void databaseQueryAllowsMultilineDamengSelectTemplates() throws Exception {
        String sql = """
            select
              etl_date,
              fund_code,
              fund_name,
              valu_sys_nav,
              corp_offweb_nav,
              ta_sys_nav,
              xbrl_sys_nav,
              veri_rslt,
              diff_expl,
              updt_time
            from
              GDP_DWD.dwd_fund_nav_consistency_check_d_i
            where etl_date = to_date('20260520', 'YYYYMMDD')
            """;

        assertThat(validateDatabaseQuerySql(sql)).isEqualTo(sql.trim());
    }

    @Test
    void databaseQueryStillRejectsWriteSqlAfterReadOnlyTokenFix() {
        assertThatThrownBy(() -> validateDatabaseQuerySql("select * from demo; update demo set name = 'x'"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Only one SQL statement is allowed");
        assertThatThrownBy(() -> validateDatabaseQuerySql("update demo set name = 'x'"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Only read-only SQL statements are allowed");
    }

    @Test
    void databaseQueryFailureMessageIncludesTheUnderlyingJdbcReason() throws Exception {
        Object tool = databaseQueryTool();
        Method formatter = tool.getClass().getDeclaredMethod("databaseQueryFailureMessage", Exception.class);
        formatter.setAccessible(true);
        SQLException sqlException = new SQLException("SemanticException: incompatible comparison types", "42000", 10014);

        String message = (String) formatter.invoke(tool,
            new RuntimeException("PreparedStatementCallback; bad SQL grammar", sqlException));

        assertThat(message)
            .contains("PreparedStatementCallback; bad SQL grammar")
            .contains("SemanticException: incompatible comparison types")
            .contains("SQLState=42000")
            .contains("errorCode=10014");
    }

    @Test
    void databaseQueryRendersResolvedSqlPreviewWithoutChangingQuotedTextOrCasts() throws Exception {
        Object tool = databaseQueryTool();
        Method renderer = tool.getClass().getDeclaredMethod("renderSqlPreview", String.class, Map.class);
        renderer.setAccessible(true);

        String sql = (String) renderer.invoke(tool,
            "select ':ignored' note where busi_date = :busi_date and name = :name and code::text = :code",
            Map.of("busi_date", "20260105", "name", "O'Reilly", "code", 7));

        assertThat(sql).isEqualTo(
            "select ':ignored' note where busi_date = '20260105' and name = 'O''Reilly' and code::text = 7");
    }

    @Test
    void databaseQueryUsesLiteralParameterCompatibilityForHiveAndInceptorOnly() throws Exception {
        Object tool = databaseQueryTool();
        Method detector = tool.getClass().getDeclaredMethod("requiresLiteralParameterExecution", ToolInput.class);
        detector.setAccessible(true);

        assertThat((Boolean) detector.invoke(tool, ToolInput.builder()
            .parameters(Map.of("database_type", "inceptor", "jdbc_url", "jdbc:hive2://tdh01:10000/default"))
            .build())).isTrue();
        assertThat((Boolean) detector.invoke(tool, ToolInput.builder()
            .parameters(Map.of("database_type", "mysql", "jdbc_url", "jdbc:mysql://db:3306/app"))
            .build())).isFalse();
    }

    @Test
    void genericToolsModuleDoesNotOwnDocumentSearch() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();
        assertThat(registry.getEnhancedTool("document_search")).isNull();
    }

    private String validateDatabaseQuerySql(String sql) throws Exception {
        Object tool = databaseQueryTool();
        Method validator = tool.getClass().getDeclaredMethod("validateReadOnlySql", String.class);
        validator.setAccessible(true);
        try {
            return (String) validator.invoke(tool, sql);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private Object databaseQueryTool() throws Exception {
        Class<?> toolClass = Class.forName("com.chatchat.tools.builtin.BuiltInToolsBootstrap$DatabaseQueryTool");
        Constructor<?> constructor = toolClass.getDeclaredConstructor(
            DynamicJdbcDriverLoader.class,
            DatabaseToolProperties.class,
            String.class,
            ObjectMapper.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
            mock(DynamicJdbcDriverLoader.class),
            new DatabaseToolProperties(),
            "",
            new ObjectMapper()
        );
    }


}
