package com.chatchat.mcpserver.ops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinuxCommandSafetyServiceTest {

    private final LinuxCommandSafetyService safetyService = new LinuxCommandSafetyService();

    @Test
    void allowsUserConfiguredCommandText() {
        assertThatCode(() -> safetyService.assertSafe("rm -rf /tmp/demo")).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankCommands() {
        assertThatThrownBy(() -> safetyService.assertSafe(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty");
    }
}
