package com.chatchat.mcpserver.livedata;

import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class LivedataApiRepository {

    private final DynamicJdbcDriverLoader driverLoader;
    private final LivedataAutoRegistrationProperties properties;

    public List<LivedataApiDefinition> findApis() {
        DataSource dataSource = driverLoader.createDataSource(
            properties.getJdbcUrl(),
            properties.getUsername(),
            properties.getPassword(),
            properties.getDriverClass()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setMaxRows(properties.getMaxApis());

        String sql = """
            select id, api_id, api_name, params, description, namespace, service_name,
                   method_name, state, version, release_version
            from %s
            %s
            order by update_time desc, create_time desc, id desc
            """.formatted(safeTableName(properties.getTableName()), whereClause());

        return jdbcTemplate.query(sql, rowMapper());
    }

    private String whereClause() {
        if (properties.isIncludeUnpublishedAsDisabled()) {
            return "";
        }
        return "where state = " + properties.getPublishedState();
    }

    private String safeTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return "ld_dataservice_api";
        }
        String trimmed = tableName.trim();
        if (!trimmed.matches("[A-Za-z0-9_.$`]+")) {
            throw new IllegalArgumentException("Invalid livedata table name: " + tableName);
        }
        return trimmed;
    }

    private RowMapper<LivedataApiDefinition> rowMapper() {
        return (rs, rowNum) -> new LivedataApiDefinition(
            rs.getString("id"),
            rs.getString("api_id"),
            rs.getString("api_name"),
            rs.getString("params"),
            rs.getString("description"),
            rs.getString("namespace"),
            rs.getString("service_name"),
            rs.getString("method_name"),
            readInteger(rs.getObject("state")),
            rs.getString("version"),
            rs.getString("release_version")
        );
    }

    private Integer readInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
