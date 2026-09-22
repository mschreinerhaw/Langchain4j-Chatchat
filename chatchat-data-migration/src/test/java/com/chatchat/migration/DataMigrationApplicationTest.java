package com.chatchat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DataMigrationApplicationTest {
    @Test
    void parentTablesAreCopiedBeforeChildren() {
        assertEquals(List.of("parent", "child", "grandchild"), DataMigrationApplication.parentFirst(
                Set.of("parent", "child", "grandchild"),
                Map.of("child", Set.of("parent"), "grandchild", Set.of("child"))));
    }

    @Test
    void foreignKeyCyclesFailBeforeWriting() {
        assertThrows(IllegalStateException.class, () -> DataMigrationApplication.parentFirst(
                Set.of("a", "b"), Map.of("a", Set.of("b"), "b", Set.of("a"))));
    }
}
