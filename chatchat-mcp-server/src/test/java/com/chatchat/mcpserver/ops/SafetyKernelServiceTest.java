package com.chatchat.mcpserver.ops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafetyKernelServiceTest {

    private final SafetyKernelService safetyKernelService = new SafetyKernelService();

    @Test
    void allowsOrdinaryUserConfiguredCommands() {
        assertThatCode(() -> safetyKernelService.assertAllowed("systemctl status nginx"))
            .doesNotThrowAnyException();
    }

    @Test
    void blocksHardForbiddenCommands() {
        assertThatThrownBy(() -> safetyKernelService.assertAllowed("rm -rf /"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("safety kernel");
        assertThatThrownBy(() -> safetyKernelService.assertAllowed("mkfs.ext4 /dev/sdb"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("safety kernel");
        assertThatThrownBy(() -> safetyKernelService.assertAllowed("dd if=/tmp/image of=/dev/sda"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("safety kernel");
        assertThatThrownBy(() -> safetyKernelService.assertAllowed("reboot"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("safety kernel");
    }
}
