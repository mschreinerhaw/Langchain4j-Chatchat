package com.chatchat.mcpserver.ops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinuxCommandSafetyServiceTest {

    private final LinuxCommandSafetyService safetyService = new LinuxCommandSafetyService();

    @Test
    void allowsReadOnlySystemOverviewCompositeCommand() {
        String command = "echo '=== 系统负载 ==='; uptime; "
            + "echo '=== 详细负载 ==='; cat /proc/loadavg; "
            + "echo '=== 内存使用 ==='; free -h; "
            + "echo '=== 磁盘使用 ==='; df -h / /boot /var /tmp 2>/dev/null; "
            + "echo '=== CPU/内存占用前20进程 ==='; top -bn1 -o %CPU | head -20; "
            + "echo '=== Docker容器状态 ==='; docker ps -a --format 'table {{.ID}}\\t{{.Image}}\\t{{.Status}}\\t{{.Names}}'";

        assertThatCode(() -> safetyService.assertSafe(command)).doesNotThrowAnyException();
    }

    @Test
    void stillBlocksDangerousCompositeCommands() {
        assertThatThrownBy(() -> safetyService.assertSafe("uptime; rm -rf /tmp/x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blocked");
    }
}
