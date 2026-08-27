package com.chatchat.mcpserver.ops.ssh;

import org.springframework.stereotype.Service;

@Service
public class LinuxCommandSafetyService {

    public void assertSafe(String command) {
        if (command == null || command.trim().isBlank()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }
    }
}
