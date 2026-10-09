package com.chatchat.agents.orchestration.analysis.report;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Keeps declared report payloads separate from prose-only cleanup; never grants verification. */
public final class ReportBlockMarkdownProtocol {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Protected(String markdown, Map<String, String> payloads) {
        public String restore(String cleaned) {
            String result = cleaned == null ? "" : cleaned;
            for (var entry : payloads.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
            return result;
        }
    }
    private ReportBlockMarkdownProtocol() { }

    public static Protected protectVerified(String markdown, Map<String, Object> metadata) {
        Object raw = ReportBlockSchema.map(metadata.get("reportBlocks")).get("blocks");
        Collection<?> admitted = raw instanceof Collection<?> list ? list : List.of();
        return protect(markdown, payload -> {
            Object block = payload.get("reportBlock");
            if (!(block instanceof Map<?, ?> map) || !"VERIFIED_SOURCE_DATA".equals(map.get("validationStatus"))) return false;
            return admitted.stream().anyMatch(expected -> {
                try { return JSON.readTree(ModelProtocolJson.compact(expected)).equals(JSON.readTree(ModelProtocolJson.compact(block))); }
                catch (Exception ignored) { return false; }
            });
        });
    }

    public static Protected protectProposals(String markdown) {
        return protect(markdown, payload -> payload.containsKey("reportBlock")
            || payload.keySet().containsAll(Set.of("chartType", "datasetRef", "encoding")));
    }

    private static Protected protect(String markdown, Predicate<Map<String, Object>> retain) {
        String source = markdown == null ? "" : markdown;
        String[] lines = source.split("(?<=\\n)", -1);
        Map<String, String> payloads = new LinkedHashMap<>();
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            var opening = Pattern.compile("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)[\\r\\n]*$").matcher(lines[i]);
            if (!opening.matches()) { output.append(lines[i]); continue; }
            String marker = opening.group(1), info = opening.group(2).trim();
            var closing = Pattern.compile("^ {0,3}" + Pattern.quote(marker.substring(0, 1)) + "{" + marker.length() + ",}[ \\t]*[\\r\\n]*$");
            int end = i + 1;
            while (end < lines.length && !closing.matcher(lines[end]).matches()) end++;
            String fence = String.join("", Arrays.copyOfRange(lines, i, Math.min(end + 1, lines.length)));
            boolean protectedPayload = false;
            if (end < lines.length && Set.of("json", "json:report-block").contains(info.toLowerCase(Locale.ROOT)) && fence.length() <= 40000) {
                try {
                    Map<String, Object> payload = JSON.readValue(String.join("", Arrays.copyOfRange(lines, i + 1, end)), new TypeReference<>() {});
                    protectedPayload = retain.test(payload);
                } catch (Exception ignored) { }
            }
            if (protectedPayload) {
                String token = "REPORTPAYLOAD" + UUID.randomUUID().toString().replace("-", "");
                String ending = fence.endsWith("\r\n") ? "\r\n" : fence.endsWith("\n") ? "\n" : "";
                payloads.put(token, fence.substring(0, fence.length() - ending.length()));
                output.append(token).append(ending);
            } else output.append(fence);
            i = end;
        }
        return new Protected(output.toString(), payloads);
    }
}
