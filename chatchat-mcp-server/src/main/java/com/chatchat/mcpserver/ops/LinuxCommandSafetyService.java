package com.chatchat.mcpserver.ops;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class LinuxCommandSafetyService {

    private static final List<String> ALLOWED_PREFIXES = List.of(
        "hostname", "uptime", "date", "whoami", "uname",
        "top", "mpstat", "sar", "free", "vmstat",
        "df", "du", "lsblk", "ping", "netstat", "ss", "ip addr", "curl",
        "ps", "jps", "pgrep", "systemctl status",
        "tail", "head", "grep", "cat", "less", "journalctl"
    );

    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
        pattern("(^|\\s)rm(\\s|$)"),
        pattern("(^|\\s)mkfs(\\.|\\s|$)"),
        pattern("(^|\\s)fdisk(\\s|$)"),
        pattern("(^|\\s)dd(\\s|$)"),
        pattern("(^|\\s)(shutdown|reboot|halt|poweroff|init)(\\s|$)"),
        pattern("kill\\s+-9"),
        pattern("(^|\\s)killall(\\s|$)"),
        pattern("chmod\\s+777"),
        pattern("chown\\s+-r"),
        pattern("(^|\\s)(passwd|userdel|groupdel|iptables|firewall-cmd|sudo|su|nohup|scp|rsync|mount|umount)(\\s|$)"),
        pattern("systemctl\\s+(stop|disable)\\b"),
        pattern("crontab\\s+-r"),
        pattern("(wget|curl).*(\\||&&|;).*sh"),
        pattern("docker\\s+(rm|rmi|stop)\\b"),
        pattern("kubectl\\s+(delete|exec|drain)\\b"),
        pattern("helm\\s+uninstall\\b")
    );

    public void assertSafe(String command) {
        String normalized = normalize(command);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }
        if (containsShellControl(normalized)) {
            throw new IllegalArgumentException("Command contains blocked shell control characters");
        }
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                throw new IllegalArgumentException("Command is blocked by runtime safety policy");
            }
        }
        boolean allowed = ALLOWED_PREFIXES.stream().anyMatch(prefix ->
            normalized.equals(prefix) || normalized.startsWith(prefix + " "));
        if (!allowed) {
            throw new IllegalArgumentException("Command is not in runtime allowlist");
        }
    }

    private boolean containsShellControl(String command) {
        return command.contains("\n")
            || command.contains("\r")
            || command.contains(";")
            || command.contains("&&")
            || command.contains("||")
            || command.contains("|")
            || command.contains("`")
            || command.contains("$(")
            || command.contains(">")
            || command.contains("<");
    }

    private String normalize(String command) {
        return command == null ? "" : command.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }
}
