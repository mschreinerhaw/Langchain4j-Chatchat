package com.chatchat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chatchat.migration.DataMigrationApplication.Column;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ColumnMigrationPlanTest {
    @Test
    void fillsMissingRequiredAuditTimesWithoutDroppingSourceColumns() throws Exception {
        LinkedHashMap<String, Column> target = new LinkedHashMap<>();
        target.put("id", new Column("id", "character varying", false, null));
        target.put("created_at", new Column("created_at", "timestamp with time zone", false, null));
        target.put("updated_at", new Column("updated_at", "timestamp with time zone", false, null));
        ColumnMigrationPlan.Plan plan = ColumnMigrationPlan.create("mcp_metadata_standard_dictionary",
                Set.of("id"), target);
        assertEquals(2, plan.filled().size());

        AtomicReference<Object> bound = new AtomicReference<>();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setObject")) {
                        bound.set(args[1]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Instant migrationTime = Instant.parse("2026-09-22T12:00:00Z");
        ColumnMigrationPlan.bindSynthetic(statement, 2, plan.filled().get(0), migrationTime,
                ZoneId.of("Asia/Shanghai"));
        assertEquals(OffsetDateTime.parse("2026-09-22T12:00:00Z"), bound.get());
    }

    @Test
    void rejectsUnknownRequiredTargetColumnsAndSourceDataLoss() {
        LinkedHashMap<String, Column> target = new LinkedHashMap<>();
        target.put("id", new Column("id", "character varying", false, null));
        target.put("required_business_field", new Column("required_business_field", "character varying", false, null));
        assertThrows(IllegalStateException.class,
                () -> ColumnMigrationPlan.create("sample", Set.of("id"), target));
        assertThrows(IllegalStateException.class,
                () -> ColumnMigrationPlan.create("sample", Set.of("id", "legacy_value"), target));
    }
}
