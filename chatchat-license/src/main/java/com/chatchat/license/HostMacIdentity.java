package com.chatchat.license;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the administrator-mounted host NIC address; never falls back to a container NIC. */
final class HostMacIdentity {
    static String read(Path file) {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(129);
            String text = new String(bytes, StandardCharsets.US_ASCII).trim();
            if (bytes.length > 128 || !text.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))
                throw new IllegalArgumentException("Expected one host NIC address");
            String normalized = MachineIdentity.normalizeMac(text);
            if ("MAC-000000000000".equals(normalized)
                    || (Integer.parseInt(text.substring(0, 2), 16) & 1) != 0)
                throw new IllegalArgumentException("Host NIC address is not unicast");
            return normalized;
        } catch (Exception invalid) {
            throw new LicenseException("Host NIC identity is unavailable or invalid", invalid);
        }
    }

    static String resolve(Path source, Path serverIdFile) {
        String identity = read(source);
        if (serverIdFile != null) {
            try {
                Path parent = serverIdFile.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(serverIdFile, identity + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (Exception failure) { throw new LicenseException("Cannot persist host NIC identity", failure); }
        }
        return identity;
    }
}
