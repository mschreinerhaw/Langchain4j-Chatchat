import com.fasterxml.jackson.databind.*;
import org.rocksdb.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Offline repair only. Scan may run read-only; apply requires the API to be stopped.
 * args: scan|apply ROCKSDB_PATH private-input.jsonl private-report.jsonl
 * Use the same rocksdbjni/Jackson libraries as the deployed API, on the host JDK 17.
 */
class ChatHistoryBodyRecovery {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !(args[0].equals("scan") || args[0].equals("apply")))
            throw new IllegalArgumentException("scan|apply ROCKSDB_PATH input.jsonl report.jsonl");
        RocksDB.loadLibrary();
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setStreamReadConstraints(mapper.getFactory().streamReadConstraints().rebuild()
            .maxStringLength(100_000_000).build());
        boolean apply = args[0].equals("apply");
        int examined = 0, missing = 0, restored = 0;
        try (Options options = new Options().setCreateIfMissing(false);
             RocksDB db = apply ? RocksDB.open(options, args[1]) : RocksDB.openReadOnly(options, args[1]);
             WriteOptions writes = new WriteOptions().setSync(true);
             WriteBatch batch = new WriteBatch();
             var input = Files.newBufferedReader(Path.of(args[2]));
             var report = Files.newBufferedWriter(Path.of(args[3]))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode row = mapper.readTree(line);
                String key = row.path("rocks_key").asText();
                if (key.isBlank()) throw new IllegalArgumentException("Missing original index key");
                byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
                examined++;
                if (db.get(bytes) != null) continue; // Existing body/reference always wins.
                missing++;
                if (apply) {
                    JsonNode detail = row.path("detail");
                    String expected = "chat:%s:%s:%013d:%s".formatted(
                        safe(detail.path("tenantId").asText()), safe(detail.path("sessionId").asText()),
                        Instant.parse(detail.path("createdAt").asText()).toEpochMilli(), safe(detail.path("messageId").asText()));
                    if (!key.equals(expected) || !row.path("role").asText().equals(detail.path("role").asText())
                        || !row.path("user_id").asText().equals(detail.path("userId").asText())
                        || !row.path("tenant_id").asText().equals(detail.path("tenantId").asText())
                        || !row.path("session_id").asText().equals(detail.path("sessionId").asText())
                        || !row.path("message_id").asText().equals(detail.path("messageId").asText()))
                        throw new IllegalArgumentException("Index/body identity mismatch; repair refused");
                    if (detail.path("content").asText().isBlank())
                        throw new IllegalArgumentException("No persisted content to restore");
                    batch.put(bytes, mapper.writeValueAsBytes(detail));
                    restored++;
                }
                report.write(line); report.newLine();
            }
            report.flush();
            if (apply) db.write(writes, batch);
        }
        System.out.println("{\"examined\":" + examined + ",\"missing\":" + missing + ",\"restored\":" + restored + "}");
    }
    private static String safe(String value) { return value.isBlank() ? "default" : value.trim().replace(':', '_'); }
}
