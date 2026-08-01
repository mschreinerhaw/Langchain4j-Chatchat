package com.chatchat.agents.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic first-line defense for instructions embedded in external evidence. */
public final class IndirectPromptInjectionDetector {

    public Detection detect(String value) {
        if (value == null || value.isBlank()) {
            return new Detection(false, List.of());
        }
        String text = value.toLowerCase(Locale.ROOT);
        List<String> signals = new ArrayList<>();
        signal(text, signals, "instruction_override", "ignore previous instruction", "ignore all instruction",
            "disregard previous", "system override", "override system", "忽略之前", "忽略所有规则", "系统覆盖");
        signal(text, signals, "privilege_or_tool_command", "admin_delete", "call admin", "invoke admin",
            "drop tenant isolation", "bypass authorization", "提升权限", "调用管理员", "绕过授权");
        signal(text, signals, "secret_exfiltration", "export tenant", "send all password", "database password",
            "reveal secret", "exfiltrate", "泄露密钥", "导出其他租户", "发送所有密码");
        signal(text, signals, "embedded_directive", "follow these instructions", "execute the following command",
            "you must now", "do not tell the user", "请执行以下命令", "不要告诉用户");
        boolean suspicious = signals.contains("instruction_override")
            || signals.contains("privilege_or_tool_command")
            || signals.contains("secret_exfiltration")
            || signals.size() >= 2;
        return new Detection(suspicious, List.copyOf(signals));
    }

    private void signal(String text, List<String> signals, String code, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                signals.add(code);
                return;
            }
        }
    }

    public record Detection(boolean suspicious, List<String> signals) {
    }
}
