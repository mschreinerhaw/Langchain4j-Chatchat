package com.chatchat.deployment.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/** Relates config IDs to OCI manifest IDs using content inside a verified archive. */
public final class ArchiveImageIdentities {
    private ArchiveImageIdentities() {}
    public static Map<String, Set<String>> read(Path archive) throws IOException {
        ObjectMapper json = new ObjectMapper();
        JsonNode index;
        try { index = json.readTree(TarArchive.entry(archive, "index.json", 1024 * 1024)); }
        catch (FileNotFoundException classicArchive) { return Map.of(); }
        Map<String, Set<String>> result = new HashMap<>();
        walk(archive, json, index, result, new HashSet<>(), 0);
        return result;
    }
    private static void walk(Path archive, ObjectMapper json, JsonNode index,
                             Map<String, Set<String>> result, Set<String> visited, int depth) throws IOException {
        if (depth > 4 || index.path("manifests").size() > 256) throw new IOException("Oversized OCI index");
        for (JsonNode descriptor : index.path("manifests")) {
            String digest = descriptor.path("digest").asText();
            if (!visited.add(digest)) continue;
            if (visited.size() > 512) throw new IOException("Too many OCI descriptors");
            byte[] bytes = blob(archive, digest, 1024 * 1024);
            JsonNode manifest = json.readTree(bytes);
            if (manifest.has("manifests")) { walk(archive, json, manifest, result, visited, depth + 1); continue; }
            String config = manifest.path("config").path("digest").asText();
            JsonNode configuration = json.readTree(blob(archive, config, 16 * 1024 * 1024));
            if (configuration.path("os").asText().equals("linux") && configuration.path("architecture").asText().equals("amd64"))
                result.computeIfAbsent(config, ignored -> new HashSet<>()).add(digest);
        }
    }
    private static byte[] blob(Path archive, String digest, int maximum) throws IOException {
        if (!digest.matches("sha256:[a-f0-9]{64}")) throw new IOException("Invalid OCI digest");
        byte[] bytes = TarArchive.entry(archive, "blobs/sha256/" + digest.substring(7), maximum);
        try {
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(digest.substring(7)))
                throw new IOException("OCI blob checksum mismatch");
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
        return bytes;
    }
}
