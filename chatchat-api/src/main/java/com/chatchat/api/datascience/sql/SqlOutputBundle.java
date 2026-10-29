package com.chatchat.api.datascience.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Validates a model's proposed source bundle; it never executes SQL or authorizes a write. */
public final class SqlOutputBundle {
    private SqlOutputBundle() { }
    public record Candidate(String path, String content) { }
    /** Read-only explanations accept Markdown; malformed JSON is never reinterpreted as source. */
    public static String explanation(String answer,ObjectMapper mapper){
        String text=Objects.toString(answer,"").strip();
        if(text.isBlank())bad("模型未返回 SQL 解释内容，请重试。");
        if(text.matches("(?is)^```json\\s*.*")){
            var fence=java.util.regex.Pattern.compile("(?is)^```json\\s*\\R?(.*?)\\R?```\\s*$").matcher(text);
            if(!fence.matches())bad("模型的 JSON 解释未完整返回，请重试；没有文件变更。");
            text=fence.group(1).strip();
        }
        if(text.startsWith("{")||text.startsWith("[")){parse(text,mapper,true);return text;}
        try{return mapper.writeValueAsString(Map.of("schemaVersion","sql-studio.output.v1","files",List.of(),"notes",text));}
        catch(Exception invalid){throw new IllegalStateException("Cannot serialize SQL explanation",invalid);}
    }
    public static String path(String value) {
        if (value == null || value.length() > 240 || value.isBlank() || !value.equals(value.trim())) bad("Invalid project path");
        String[] parts = value.split("/", -1);
        for (String part : parts) {
            if (!part.matches("[\\p{L}\\p{N}_][\\p{L}\\p{N}_. -]{0,179}") || part.endsWith(".") || part.endsWith(" ")
                || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) bad("Unsafe project path");
        }
        return value;
    }
    public static List<Candidate> parse(String answer, ObjectMapper mapper) {
        return parse(answer, mapper, false);
    }
    public static List<Candidate> parse(String answer, ObjectMapper mapper, boolean explanation) {
        return parseLanguage(answer, mapper, explanation, false);
    }
    public static List<Candidate> parsePython(String answer, ObjectMapper mapper) {
        return parseLanguage(answer, mapper, false, true);
    }
    private static List<Candidate> parseLanguage(String answer, ObjectMapper mapper, boolean explanation, boolean python) {
        String json = answer == null ? "" : answer.trim();
        if (json.startsWith("```json") && json.endsWith("```")) json = json.substring(7, json.length() - 3).trim();
        try {
            var root = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (root == null || !(python?"python-studio.output.v1":"sql-studio.output.v1").equals(root.path("schemaVersion").asText()) || !root.path("files").isArray())
                bad("Model result is not a sql-studio.output.v1 file bundle");
            List<Candidate> result = new ArrayList<>(); Set<String> names = new HashSet<>();
            if (explanation) {
                if (!root.path("files").isEmpty() || !root.path("notes").isTextual() || root.path("notes").asText().isBlank())
                    bad("An explanation must contain notes and no proposed SQL files");
                return List.of();
            }
            for (var file : root.path("files")) {
                if (!file.path("path").isTextual() || !file.path("content").isTextual()) bad("SQL path and content must be strings");
                String name = path(file.path("path").asText());
                String content = file.path("content").asText("");
                if (!name.toLowerCase(Locale.ROOT).endsWith(python?".py":".sql") || !names.add(name.toLowerCase(Locale.ROOT))
                    || content.isBlank() || content.length() > 200_000 || result.size() >= 32) bad("Invalid SQL output file");
                result.add(new Candidate(name, content));
            }
            if (result.isEmpty()) bad("Model result contains no SQL files");
            return List.copyOf(result);
        } catch (ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { bad("Model result is not valid JSON; review the raw answer"); return List.of(); }
    }
    private static void bad(String reason) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
}
