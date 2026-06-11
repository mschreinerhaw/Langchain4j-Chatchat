package com.chatchat.mcpserver.mcp;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class McpTokenGenerator {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Performs the generate operation.
     *
     * @return the operation result
     */
    public String generate() {
        byte[] bytes = new byte[12];
        secureRandom.nextBytes(bytes);
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0f];
        }
        return "mcp_" + new String(chars);
    }
}
