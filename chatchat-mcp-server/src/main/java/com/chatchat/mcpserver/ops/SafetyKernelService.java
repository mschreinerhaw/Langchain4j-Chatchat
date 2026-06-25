package com.chatchat.mcpserver.ops;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class SafetyKernelService {

    private static final List<Pattern> HARD_FORBIDDEN = List.of(
        pattern(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*}\\s*;\\s*:"),
        pattern("\\brm\\s+-(?:[a-z]*r[a-z]*f[a-z]*|[a-z]*f[a-z]*r[a-z]*)\\s+(?:--no-preserve-root\\s+)?(?:/|/\\*|/\\.)\\s*(?:$|[;&|])"),
        pattern("\\bmkfs(?:\\.[a-z0-9_+-]+)?\\b"),
        pattern("\\bdd\\b(?=.*\\bif\\s*=)(?=.*\\bof\\s*=\\s*/dev/)"),
        pattern("\\b(?:shutdown|reboot|halt|poweroff)\\b"),
        pattern("\\binit\\s+[06]\\b")
    );

    public void assertAllowed(String command) {
        String normalized = normalize(command);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }
        for (Pattern pattern : HARD_FORBIDDEN) {
            if (pattern.matcher(normalized).find()) {
                throw new IllegalArgumentException("Command is blocked by immutable safety kernel");
            }
        }
    }

    private String normalize(String command) {
        return command == null ? "" : command.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }
}
