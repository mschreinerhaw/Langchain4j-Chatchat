package com.chatchat.mcpserver.ops;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class LinuxCommandSafetyService {

    private static final List<String> ALLOWED_PREFIXES = List.of(
        "hostname", "uptime", "date", "whoami", "uname",
        "echo",
        "top", "mpstat", "sar", "free", "vmstat",
        "df", "du", "lsblk", "ping", "netstat", "ss", "ip addr", "curl",
        "ps", "jps", "pgrep", "systemctl status",
        "tail", "head", "grep", "cat", "less", "journalctl",
        "docker ps"
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
        if (containsBlockedShellControl(normalized)) {
            throw new IllegalArgumentException("Command contains blocked shell control characters");
        }
        for (String segment : splitOutsideQuotes(normalized, ';')) {
            assertSafeSegment(segment);
        }
    }

    private void assertSafeSegment(String segment) {
        String normalized = stripAllowedStderrRedirect(segment == null ? "" : segment.trim());
        if (normalized.isBlank()) {
            return;
        }
        List<String> pipeline = splitOutsideQuotes(normalized, '|').stream()
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
        if (pipeline.isEmpty() || pipeline.size() > 2) {
            throw new IllegalArgumentException("Command pipeline is not allowed by runtime safety policy");
        }
        assertSafeSimpleCommand(pipeline.get(0), false);
        if (pipeline.size() == 2) {
            assertSafeSimpleCommand(pipeline.get(1), true);
        }
    }

    private void assertSafeSimpleCommand(String command, boolean pipelineTail) {
        String normalized = stripAllowedStderrRedirect(command == null ? "" : command.trim());
        if (normalized.isBlank()) {
            return;
        }
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                throw new IllegalArgumentException("Command is blocked by runtime safety policy");
            }
        }
        List<String> prefixes = pipelineTail ? List.of("head", "tail", "grep", "cat") : ALLOWED_PREFIXES;
        boolean allowed = prefixes.stream().anyMatch(prefix ->
            normalized.equals(prefix) || normalized.startsWith(prefix + " "));
        if (!allowed) {
            throw new IllegalArgumentException("Command is not in runtime allowlist");
        }
    }

    private boolean containsBlockedShellControl(String command) {
        return command.contains("\n")
            || command.contains("\r")
            || command.contains("&&")
            || command.contains("||")
            || command.contains("`")
            || command.contains("$(")
            || command.contains("<")
            || containsBlockedRedirect(command);
    }

    private boolean containsBlockedRedirect(String command) {
        String withoutAllowed = command.replaceAll("\\s+2>\\s*/dev/null\\b", " ");
        return withoutAllowed.contains(">");
    }

    private String stripAllowedStderrRedirect(String command) {
        return command.replaceAll("\\s+2>\\s*/dev/null\\b", " ").trim();
    }

    private List<String> splitOutsideQuotes(String command, char delimiter) {
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < command.length(); index++) {
            char ch = command.charAt(index);
            if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
            } else if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
            }
            if (ch == delimiter && !singleQuoted && !doubleQuoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private String normalize(String command) {
        return command == null ? "" : command.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }
}
