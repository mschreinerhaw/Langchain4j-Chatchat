package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.evidence.normalization.EvidenceType;

import com.chatchat.agents.evidence.answer.EvidenceAnswer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the immutable evidence manifest and sentence-level claim ledger used by the final answer gate. */
public final class AnswerEvidenceLedgerCompiler {

    static final String CLAIM_LEDGER_VERSION = "claim_ledger_v1";
    static final String EVIDENCE_MANIFEST_VERSION = "evidence_manifest_v1";
    private static final Pattern EVIDENCE_REF = Pattern.compile(
        "(?:tool://[^\\s\\[\\]()<>，。；、！？,;]+#result=[^\\s\\[\\]()<>，。；、！？,;]+"
            + "|doc://[^\\s\\[\\]()<>，。；、！？,;]+#chunk=[^\\s\\[\\]()<>，。；、！？,;]+"
            + "|web://[^\\s\\[\\]()<>，。；、！？,;]+#result=[^\\s\\[\\]()<>，。；、！？,;]+"
            + "|https?://[^\\s\\[\\]()<>，。；、！？,;]+)");
    private static final Pattern LEGACY_TOOL_REF = Pattern.compile(
        "tool://(?:([a-zA-Z0-9._:-]+)/)?([a-zA-Z0-9._:-]+)"
            + "(?=$|[\\s\\[\\]()<>.,;!?，。；、！？])");
    private static final Pattern EVIDENCE_ID_ALIAS = Pattern.compile(
        "(?i)evidenceId\\s*[:=]\\s*([a-zA-Z0-9._:-]+)");
    private static final Pattern EVIDENCE_BLOCK = Pattern.compile(
        "(?is)(?:citation|refId|sourceRef)\\s*[:=]\\s*((?:doc://[^\\s,;，。；、！？]+#chunk=[^\\s,;，。；、！？]+"
            + "|web://[^\\s,;，。；、！？]+#result=[^\\s,;，。；、！？]+)).*?"
            + "(?:content|text|snippet)\\s*[:=]\\s*(.*?)(?=\\n\\s*\\[Evidence|"
            + "\\n\\s*(?:citation|refId|sourceRef)\\s*[:=]|\\z)");
    private static final Pattern NUMBER_OR_DATE = Pattern.compile(
        "(?:\\d+(?:[.,]\\d+)?%?|\\d{4}(?:[-/年]\\d{1,2}(?:[-/月]\\d{1,2}日?)?))");
    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9_-]{2,}|[\\p{IsHan}]{2,}");
    private static final Pattern AGGREGATE_COUNT = Pattern.compile(
        "(?i)(?:\\b(?:count|total)\\s*(?:is|=|:)?\\s*\\d+"
            + "|\\b\\d+\\s+(?:records?|rows?|items?|images?|containers?|sessions?|errors?)\\b"
            + "|\\d+\\s*(?:个|条|行|项|台|张|笔|次)\\s*"
            + "(?:[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9_-]{0,16})?)");
    private static final Pattern STRUCTURED_NUMERIC_FIELD = Pattern.compile(
        "[\\\"]([A-Za-z][A-Za-z0-9_]*)[\\\"]\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");

    public Result compile(String answer, Map<String, Object> metadata, List<String> observations,
                   List<Map<String, Object>> toolEvidence) {
        Map<String, EvidenceItem> manifestItems = manifestItems(metadata, observations, toolEvidence);
        List<Map<String, Object>> claims = claims(answer, manifestItems);
        int material = claims.size();
        int verified = (int) claims.stream()
            .filter(item -> String.valueOf(item.get("verification")).startsWith("VERIFIED")).count();
        int criticalUnbound = (int) claims.stream()
            .filter(item -> "UNBOUND".equals(item.get("verification")))
            .filter(item -> "HIGH".equals(item.get("risk"))).count();
        int unknown = (int) claims.stream()
            .filter(item -> "UNKNOWN_REFERENCE".equals(item.get("verification"))).count();
        boolean evidenceApplicable = manifestItems.values().stream()
            .anyMatch(item -> "TRUSTED".equals(item.trustStatus())) || unknown > 0;
        double coverage = !evidenceApplicable || material == 0 ? 1.0 : round((double) verified / material);
        String status = unknown > 0 || criticalUnbound > 0
            ? "FAIL" : !evidenceApplicable ? "NOT_APPLICABLE" : coverage >= 0.999 ? "PASS" : "PARTIAL";

        Map<String, Object> claimLedger = new LinkedHashMap<>();
        claimLedger.put("contractVersion", CLAIM_LEDGER_VERSION);
        claimLedger.put("status", status);
        claimLedger.put("coverage", coverage);
        claimLedger.put("materialClaimCount", material);
        claimLedger.put("verifiedClaimCount", verified);
        claimLedger.put("criticalUnboundClaimCount", criticalUnbound);
        claimLedger.put("unknownReferenceCount", unknown);
        claimLedger.put("claims", List.copyOf(claims));
        claimLedger.put("ledgerHash", sha256(stableText(claims)));

        List<Map<String, Object>> manifest = manifestItems.values().stream().map(EvidenceItem::toMap).toList();
        Map<String, Object> evidenceManifest = new LinkedHashMap<>();
        evidenceManifest.put("contractVersion", EVIDENCE_MANIFEST_VERSION);
        evidenceManifest.put("tenantId", text(metadata == null ? null : metadata.get("tenantId")));
        evidenceManifest.put("runId", firstText(metadata, "agentRunId", "runId", "requestId"));
        evidenceManifest.put("evidenceCount", manifest.size());
        evidenceManifest.put("items", manifest);
        evidenceManifest.put("manifestHash", sha256(stableText(manifest)));
        return new Result(Map.copyOf(claimLedger), Map.copyOf(evidenceManifest), status, coverage,
            criticalUnbound, unknown);
    }

    /**
     * Adds exact, returned evidence URIs to factual Markdown lines before model review.
     * The binding is derived only from the current manifest: explicit value matches,
     * a Markdown section that names a returned tool, or a conservative semantic match.
     * Advice, commands and safety instructions are intentionally outside the factual
     * claim ledger and are never decorated merely to improve a score.
     */
    public BindingResult bindReturnedEvidence(String answer,
                                       Map<String, Object> metadata,
                                       List<String> observations,
                                       List<Map<String, Object>> toolEvidence) {
        if (answer == null || answer.isBlank()) {
            return new BindingResult(answer == null ? "" : answer, 0);
        }
        Map<String, EvidenceItem> manifest = manifestItems(metadata, observations, toolEvidence);
        if (manifest.isEmpty()) {
            return new BindingResult(answer, 0);
        }
        LegacyReferenceNormalization legacyNormalization = normalizeLegacyToolReferences(answer, manifest);
        answer = legacyNormalization.answer();
        LegacyReferenceNormalization evidenceIdNormalization = normalizeEvidenceIdAliases(answer, manifest);
        answer = evidenceIdNormalization.answer();
        Map<String, List<String>> refsByTool = new LinkedHashMap<>();
        for (EvidenceItem item : manifest.values()) {
            if (!"TRUSTED".equals(item.trustStatus()) || item.toolName().isBlank()) continue;
            refsByTool.computeIfAbsent(item.toolName().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                .add(item.evidenceId());
        }

        String[] lines = answer.replace("\r", "").split("\n", -1);
        List<String> activeSectionRefs = List.of();
        boolean codeFence = false;
        boolean nonFactualSection = false;
        int bound = 0;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                codeFence = !codeFence;
                continue;
            }
            if (codeFence || trimmed.isBlank() || trimmed.startsWith("|") || trimmed.startsWith(">")) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                activeSectionRefs = toolRefsMentioned(trimmed, refsByTool);
                nonFactualSection = isNonFactualSectionHeading(trimmed);
                continue;
            }
            if (nonFactualSection) continue;
            LineBinding lineBinding = bindProseLine(line, activeSectionRefs, manifest);
            lines[index] = lineBinding.text();
            bound += lineBinding.boundCount();
        }
        return new BindingResult(String.join("\n", lines), bound + legacyNormalization.replacementCount()
            + evidenceIdNormalization.replacementCount());
    }

    private LineBinding bindProseLine(String line, List<String> activeSectionRefs,
                                      Map<String, EvidenceItem> manifest) {
        String[] sentences = (line == null ? "" : line)
            .split("(?<=[。！？!?])|(?<=\\.)\\s+");
        List<String> values = new ArrayList<>();
        int bound = 0;
        for (String sentence : sentences) {
            String working = sentence;
            String trimmed = working.trim();
            if (!isMaterial(trimmed)) {
                values.add(working);
                continue;
            }
            Set<String> existingRefs = refs(trimmed);
            if (!existingRefs.isEmpty() && existingRefs.stream()
                .anyMatch(ref -> eligibleExplicitReference(ref, manifest))) {
                values.add(working);
                continue;
            }
            if (!existingRefs.isEmpty()) {
                working = removeIneligibleToolReferences(working, manifest);
                trimmed = working.trim();
            }
            List<String> bindings = activeSectionRefs == null || activeSectionRefs.isEmpty()
                ? List.of() : activeSectionRefs;
            if (bindings.isEmpty()) bindings = inferredEvidenceRefs(trimmed, manifest);
            if (bindings.isEmpty()) bindings = semanticEvidenceRefs(trimmed, manifest);
            if (bindings.isEmpty()) {
                values.add(working);
                continue;
            }
            List<String> distinct = bindings.stream().distinct().toList();
            values.add(appendEvidenceBeforeTerminalPunctuation(
                working, " [evidence: " + String.join(", ", distinct) + "]"));
            bound++;
        }
        List<String> paragraphRefs = values.stream()
            .flatMap(value -> refs(value).stream())
            .filter(ref -> eligibleExplicitReference(ref, manifest))
            .distinct()
            .toList();
        if (!paragraphRefs.isEmpty() && values.size() > 1) {
            for (int index = 0; index < values.size(); index++) {
                String value = values.get(index);
                if (!isMaterial(value.trim()) || !refs(value).isEmpty()) continue;
                values.set(index, appendEvidenceBeforeTerminalPunctuation(
                    value, " [evidence: " + String.join(", ", paragraphRefs) + "]"));
                bound++;
            }
        }
        return new LineBinding(String.join(" ", values), bound);
    }

    /**
     * Older prompts sometimes emit an opaque tool-call URI (tool://tool/uuid or tool://uuid) even though
     * the governed manifest exposes result-set URIs. Resolve that alias from the complete
     * document context and current manifest; never trust the opaque identifier itself.
     */
    private LegacyReferenceNormalization normalizeLegacyToolReferences(
        String answer, Map<String, EvidenceItem> manifest) {
        Map<String, StringBuilder> contexts = new LinkedHashMap<>();
        for (String line : (answer == null ? "" : answer).replace("\r", "").split("\n")) {
            Matcher lineMatcher = LEGACY_TOOL_REF.matcher(line);
            while (lineMatcher.find()) {
                contexts.computeIfAbsent(lineMatcher.group(), ignored -> new StringBuilder())
                    .append(' ').append(line);
            }
        }
        if (contexts.isEmpty()) return new LegacyReferenceNormalization(answer, 0);

        Map<String, String> aliases = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> entry : contexts.entrySet()) {
            Matcher aliasMatcher = LEGACY_TOOL_REF.matcher(entry.getKey());
            if (!aliasMatcher.matches()) continue;
            String executor = aliasMatcher.group(1);
            List<EvidenceItem> candidates = eligibleFactEvidence(manifest).stream()
                .filter(item -> "TRUSTED".equals(item.trustStatus()))
                .filter(item -> executor == null
                    || item.evidenceId().startsWith("tool://" + executor + "#result="))
                .toList();
            String resolved = bestContextEvidence(entry.getValue().toString(), candidates);
            if (!resolved.isBlank()) aliases.put(entry.getKey(), resolved);
        }
        if (aliases.isEmpty()) return new LegacyReferenceNormalization(answer, 0);
        String normalized = answer;
        int replacements = 0;
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            int occurrences = countOccurrences(normalized, alias.getKey());
            if (occurrences == 0) continue;
            normalized = normalized.replace(alias.getKey(), alias.getValue());
            replacements += occurrences;
        }
        return new LegacyReferenceNormalization(normalized, replacements);
    }

    /** Resolves evidenceId=: and evidenceId: aliases emitted by model/reviewer variants. */
    private LegacyReferenceNormalization normalizeEvidenceIdAliases(
        String answer, Map<String, EvidenceItem> manifest) {
        Map<String, StringBuilder> contexts = new LinkedHashMap<>();
        for (String line : (answer == null ? "" : answer).replace("\r", "").split("\n")) {
            Matcher lineMatcher = EVIDENCE_ID_ALIAS.matcher(line);
            while (lineMatcher.find()) {
                contexts.computeIfAbsent(lineMatcher.group(), ignored -> new StringBuilder())
                    .append(' ').append(line);
            }
        }
        if (contexts.isEmpty()) return new LegacyReferenceNormalization(answer, 0);

        List<EvidenceItem> allCandidates = eligibleFactEvidence(manifest).stream()
            .filter(item -> "TRUSTED".equals(item.trustStatus()))
            .filter(item -> item.evidenceId().startsWith("tool://"))
            .toList();
        Map<String, String> aliases = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> entry : contexts.entrySet()) {
            String context = entry.getValue().toString();
            List<EvidenceItem> scoped = allCandidates.stream()
                .filter(item -> context.toLowerCase(Locale.ROOT)
                    .contains(toolExecutor(item.evidenceId()).toLowerCase(Locale.ROOT)))
                .toList();
            String resolved = bestContextEvidence(context, scoped.isEmpty() ? allCandidates : scoped);
            if (!resolved.isBlank()) aliases.put(entry.getKey(), resolved);
        }
        if (aliases.isEmpty()) return new LegacyReferenceNormalization(answer, 0);
        String normalized = answer;
        int replacements = 0;
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            int occurrences = countOccurrences(normalized, alias.getKey());
            if (occurrences == 0) continue;
            normalized = normalized.replace(alias.getKey(), alias.getValue());
            replacements += occurrences;
        }
        return new LegacyReferenceNormalization(normalized, replacements);
    }

    private String toolExecutor(String evidenceId) {
        if (evidenceId == null || !evidenceId.startsWith("tool://")) return "";
        int end = evidenceId.indexOf("#result=");
        return end > "tool://".length() ? evidenceId.substring("tool://".length(), end) : "";
    }

    private boolean eligibleExplicitReference(String ref, Map<String, EvidenceItem> manifest) {
        EvidenceItem item = manifest.get(ref);
        if (item == null || !"TRUSTED".equals(item.trustStatus())) return false;
        boolean hasResultSets = manifest.values().stream()
            .anyMatch(value -> "TOOL_RESULT_SET".equals(value.type()));
        return !hasResultSets || !ref.startsWith("tool://") || "TOOL_RESULT_SET".equals(item.type());
    }

    private String removeIneligibleToolReferences(String line, Map<String, EvidenceItem> manifest) {
        Matcher matcher = EVIDENCE_REF.matcher(line == null ? "" : line);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String ref = cleanRef(matcher.group());
            matcher.appendReplacement(result, eligibleExplicitReference(ref, manifest)
                ? Matcher.quoteReplacement(matcher.group()) : "");
        }
        matcher.appendTail(result);
        return result.toString()
            .replaceAll("(?i)\\[\\s*evidence\\s*:\\s*]", "")
            .replaceAll("\\[\\s*]", "")
            .replaceAll("\\s{2,}", " ");
    }

    private String bestContextEvidence(String context, List<EvidenceItem> candidates) {
        if (candidates.isEmpty()) return "";
        if (candidates.size() == 1) return candidates.get(0).evidenceId();
        Set<String> contextTerms = terms(context);
        Set<String> contextValues = new LinkedHashSet<>(matchedValues(context));
        int bestScore = 0;
        int secondScore = 0;
        EvidenceItem best = null;
        for (EvidenceItem candidate : candidates) {
            Set<String> evidenceTerms = terms(candidate.contentPreview());
            String comparable = normalizedComparable(candidate.contentPreview());
            int termScore = (int) contextTerms.stream().filter(evidenceTerms::contains).count();
            int valueScore = (int) contextValues.stream()
                .filter(value -> comparable.contains(normalizedComparable(value))).count() * 3;
            int score = termScore + valueScore;
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = candidate;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        return best != null && bestScore >= 2 && bestScore > secondScore
            ? best.evidenceId() : "";
    }

    private int countOccurrences(String value, String token) {
        if (value == null || token == null || token.isEmpty()) return 0;
        int count = 0;
        for (int index = 0; (index = value.indexOf(token, index)) >= 0; index += token.length()) count++;
        return count;
    }

    private String appendEvidenceBeforeTerminalPunctuation(String line, String evidence) {
        if (line == null || line.isEmpty()) return evidence.trim();
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        int punctuationStart = end;
        while (punctuationStart > 0
            && "。；！？.!?;".indexOf(line.charAt(punctuationStart - 1)) >= 0) {
            punctuationStart--;
        }
        return line.substring(0, punctuationStart) + evidence
            + line.substring(punctuationStart, end) + line.substring(end);
    }

    private List<String> toolRefsMentioned(String line, Map<String, List<String>> refsByTool) {
        String normalized = line == null ? "" : line.toLowerCase(Locale.ROOT);
        return refsByTool.entrySet().stream()
            .filter(entry -> normalized.contains(entry.getKey()))
            .flatMap(entry -> entry.getValue().stream())
            .distinct()
            .toList();
    }

    /** Value-free summary conclusions may cite strongly related returned result sets. */
    private List<String> semanticEvidenceRefs(String claimText, Map<String, EvidenceItem> manifest) {
        if (!matchedValues(claimText).isEmpty() || isNormativeGuidance(claimText)) return List.of();
        Set<String> claimTerms = terms(claimText);
        if (claimTerms.size() < 2) return List.of();
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (EvidenceItem item : eligibleFactEvidence(manifest)) {
            if (!"TRUSTED".equals(item.trustStatus()) || item.contentPreview().isBlank()) continue;
            Set<String> evidenceTerms = terms(item.contentPreview());
            int score = (int) claimTerms.stream().filter(evidenceTerms::contains).count();
            if (score >= 2) scores.put(item.evidenceId(), score);
        }
        int best = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (best < 2) return List.of();
        return scores.entrySet().stream()
            .filter(entry -> entry.getValue() >= Math.max(2, best - 1))
            .map(Map.Entry::getKey)
            .toList();
    }

    private Map<String, EvidenceItem> manifestItems(Map<String, Object> metadata, List<String> observations,
                                                    List<Map<String, Object>> toolEvidence) {
        Map<String, String> contents = evidenceContents(observations);
        Map<String, EvidenceItem> items = new LinkedHashMap<>();
        addCitationObjects(items, metadata == null ? null : metadata.get("availableEvidenceCitations"), contents);
        Map<String, Object> evidenceAnswer = map(metadata == null ? null : metadata.get("evidenceAnswer"));
        addCitationObjects(items, evidenceAnswer.get("citations"), contents);
        String joined = observations == null ? "" : String.join("\n", observations);
        Matcher refs = EVIDENCE_REF.matcher(joined);
        while (refs.find()) {
            String ref = cleanRef(refs.group());
            items.putIfAbsent(ref, evidence(ref, evidenceType(ref), contents.get(ref), null, true));
        }
        int index = 0;
        for (Map<String, Object> tool : toolEvidence == null ? List.<Map<String, Object>>of() : toolEvidence) {
            index++;
            String toolName = text(tool.get("toolName"));
            String ref = "tool://" + (toolName.isBlank() ? "unknown" : toolName) + "#result=" + index;
            String content = firstNonBlank(tool.get("outputPreview"), tool.get("bodyPreview"),
                tool.get("stdoutPreview"), tool.get("sampleRows"));
            boolean trusted = Boolean.TRUE.equals(tool.get("success"));
            Object rawChildren = tool.get("resultSetEvidence");
            boolean hasChildren = rawChildren instanceof List<?> && !((List<?>) rawChildren).isEmpty();
            if (!content.isBlank() || !hasChildren) {
                items.putIfAbsent(ref, evidence(ref, "TOOL", content, toolName, trusted));
            }
            if (rawChildren instanceof List<?> children) {
                int childIndex = 0;
                for (Object rawChild : children) {
                    Map<String, Object> child = map(rawChild);
                    if (child.isEmpty()) continue;
                    childIndex++;
                    String childRef = ref + "/child=" + childIndex;
                    String childContent = firstNonBlank(child.get("outputPreview"), child.get("bodyPreview"),
                        child.get("stdoutPreview"), child.get("sampleRows"));
                    boolean childTrusted = trusted && !Boolean.FALSE.equals(child.get("success"));
                    items.putIfAbsent(childRef, evidence(childRef, "TOOL_RESULT_SET", childContent,
                        firstNonBlank(child.get("templateId"), child.get("callId"), toolName), childTrusted));
                }
            }
        }
        return items;
    }

    private void addCitationObjects(Map<String, EvidenceItem> items, Object raw, Map<String, String> contents) {
        if (!(raw instanceof List<?> list)) return;
        for (Object value : list) {
            Map<String, Object> citation = map(value);
            String ref = firstNonBlank(citation.get("refId"), citation.get("sourceRef"), citation.get("url"));
            if (ref.isBlank()) continue;
            String content = firstNonBlank(citation.get("text"), citation.get("content"),
                citation.get("snippet"), contents.get(ref));
            items.putIfAbsent(ref, evidence(ref, firstNonBlank(citation.get("type"), evidenceType(ref)),
                content, firstNonBlank(citation.get("toolName"), citation.get("source")), true));
        }
    }

    private Map<String, String> evidenceContents(List<String> observations) {
        Map<String, String> values = new LinkedHashMap<>();
        String joined = observations == null ? "" : String.join("\n", observations);
        Matcher matcher = EVIDENCE_BLOCK.matcher(joined);
        while (matcher.find()) {
            values.putIfAbsent(cleanRef(matcher.group(1)), limit(normalize(matcher.group(2)), 4_000));
        }
        return values;
    }

    private List<Map<String, Object>> claims(String answer, Map<String, EvidenceItem> manifest) {
        if (answer == null || answer.isBlank()) return List.of();
        List<Map<String, Object>> claims = new ArrayList<>();
        int id = 0;
        boolean codeFence = false;
        boolean nonFactualSection = false;
        for (String block : answer.replace("\r", "").split("\n+")) {
            String line = block.trim();
            if (line.startsWith("```")) {
                codeFence = !codeFence;
                continue;
            }
            if (line.startsWith("#")) {
                nonFactualSection = isNonFactualSectionHeading(line);
                continue;
            }
            if (codeFence || nonFactualSection || line.isBlank() || line.startsWith("|")
                || line.matches("^[|: \\-]+$")) {
                continue;
            }
            line = line.replaceFirst("^\\s*(?:[-*+]\\s+|\\d+[.)\\u3001]\\s*)", "").trim();
            for (String sentence : line.split("(?<=[\\u3002\\uFF01\\uFF1F!?])|(?<=\\.)\\s+")) {
                String claimText = sentence.trim();
                if (!isMaterial(claimText) && refs(claimText).isEmpty()) continue;
                id++;
                Set<String> refs = refs(claimText);
                List<String> known = refs.stream()
                    .filter(ref -> eligibleExplicitReference(ref, manifest)).toList();
                List<String> unknown = refs.stream().filter(ref -> !manifest.containsKey(ref)).toList();
                List<String> inferred = refs.isEmpty() ? inferredEvidenceRefs(claimText, manifest) : List.of();
                String verification = !unknown.isEmpty() ? "UNKNOWN_REFERENCE"
                    : !known.isEmpty() ? "VERIFIED"
                    : !inferred.isEmpty() ? "VERIFIED_VALUE_MATCH"
                    : manifest.isEmpty() ? "NO_EXTERNAL_EVIDENCE" : "UNBOUND";
                String risk = NUMBER_OR_DATE.matcher(claimText).find()
                    || (strongClaim(claimText) && !isNormativeGuidance(claimText)) ? "HIGH" : "NORMAL";
                Map<String, Object> claim = new LinkedHashMap<>();
                claim.put("claimId", "C" + id);
                claim.put("text", claimText);
                claim.put("material", true);
                claim.put("risk", risk);
                claim.put("evidenceRefs", known.isEmpty() ? inferred : known);
                claim.put("unknownRefs", unknown);
                claim.put("verification", verification);
                claim.put("bindingMode", !known.isEmpty() ? "EXPLICIT_REFERENCE"
                    : !inferred.isEmpty() ? "DETERMINISTIC_VALUE_MATCH" : "NONE");
                claim.put("claimHash", sha256(claimText));
                claims.add(Map.copyOf(claim));
            }
        }
        return List.copyOf(claims);
    }

    /** Narrow structured-value binding: all values and at least one meaningful term must match. */
    private List<String> inferredEvidenceRefs(String claimText, Map<String, EvidenceItem> manifest) {
        List<String> claimValues = matchedValues(claimText);
        if (claimValues.isEmpty()) return List.of();
        Set<String> claimTerms = terms(claimText);
        List<EvidenceItem> eligibleEvidence = eligibleFactEvidence(manifest);
        if (!aggregateCountsSupported(claimText, eligibleEvidence)) return List.of();
        long trustedEvidenceCount = eligibleEvidence.stream()
            .filter(item -> "TRUSTED".equals(item.trustStatus()) && !item.contentPreview().isBlank())
            .count();
        List<String> matches = new ArrayList<>();
        for (EvidenceItem item : eligibleEvidence) {
            if (!"TRUSTED".equals(item.trustStatus()) || item.contentPreview().isBlank()) continue;
            Set<String> evidenceTerms = terms(item.contentPreview());
            boolean soleTrustedEvidence = trustedEvidenceCount == 1;
            if (allValuesHaveLocalContext(claimValues, claimText, claimTerms, item.contentPreview(),
                !soleTrustedEvidence)
                && (soleTrustedEvidence
                || claimTerms.stream().anyMatch(evidenceTerms::contains))) {
                matches.add(item.evidenceId());
            }
        }
        if (!matches.isEmpty()) return List.copyOf(matches);

        // A factual sentence may synthesize values from several result sets of the
        // same governed batch. Bind it to every contributing child when the trusted
        // evidence union contains all values and every selected child also shares a
        // meaningful domain term. This keeps the gate strict without requiring the
        // model to force one sentence per physical result set.
        List<EvidenceItem> contributors = eligibleEvidence.stream()
            .filter(item -> "TRUSTED".equals(item.trustStatus()) && !item.contentPreview().isBlank())
            .filter(item -> {
                Set<String> evidenceTerms = terms(item.contentPreview());
                return hasAnyValueWithLocalContext(claimValues, claimText, claimTerms, item.contentPreview(), true)
                    && claimTerms.stream().anyMatch(evidenceTerms::contains);
            })
            .toList();
        boolean unionContainsAllValues = claimValues.stream().allMatch(value -> contributors.stream()
            .anyMatch(item -> valueHasLocalContext(value, claimTerms, item.contentPreview(), true)
                || percentageHasLocalContext(value, claimText, claimTerms, item.contentPreview(), true)));
        if (unionContainsAllValues) {
            return contributors.stream().map(EvidenceItem::evidenceId).toList();
        }
        return List.copyOf(matches);
    }

    /** Runtime result sets take precedence over discovery/catalog payloads for factual value binding. */
    private List<EvidenceItem> eligibleFactEvidence(Map<String, EvidenceItem> manifest) {
        List<EvidenceItem> resultSets = manifest.values().stream()
            .filter(item -> "TOOL_RESULT_SET".equals(item.type()))
            .toList();
        return resultSets.isEmpty() ? List.copyOf(manifest.values()) : resultSets;
    }

    /** Aggregate counts are verified only by the same count phrase/field, never by scattered digits. */
    private boolean aggregateCountsSupported(String claimText, List<EvidenceItem> evidence) {
        Matcher matcher = AGGREGATE_COUNT.matcher(claimText == null ? "" : claimText);
        while (matcher.find()) {
            String aggregate = normalizedComparable(matcher.group());
            boolean supported = evidence.stream()
                .filter(item -> "TRUSTED".equals(item.trustStatus()))
                .map(EvidenceItem::contentPreview)
                .map(this::normalizedComparable)
                .anyMatch(content -> content.contains(aggregate));
            if (!supported) {
                List<String> values = matchedValues(matcher.group());
                Set<String> aggregateTerms = terms(matcher.group());
                supported = !values.isEmpty() && evidence.stream()
                    .filter(item -> "TRUSTED".equals(item.trustStatus()))
                    .map(EvidenceItem::contentPreview)
                    .anyMatch(content -> values.stream().allMatch(value ->
                        valueHasLocalContext(value, aggregateTerms, content, true)));
            }
            if (!supported) {
                List<String> claimValues = matchedValues(claimText);
                Set<String> claimTerms = terms(claimText);
                supported = !claimValues.isEmpty() && evidence.stream()
                    .filter(item -> "TRUSTED".equals(item.trustStatus()))
                    .map(EvidenceItem::contentPreview)
                    .flatMap(content -> List.of(content.split("\\R")).stream())
                    .anyMatch(line -> {
                        String comparable = normalizedComparable(line);
                        Set<String> lineTerms = terms(line);
                        return claimValues.stream().allMatch(value ->
                            comparable.contains(normalizedComparable(value)))
                            && claimTerms.stream().anyMatch(lineTerms::contains);
                    });
            }
            if (!supported) return false;
        }
        return true;
    }

    /** Every value must occur on a physical evidence line that also carries a claim-domain term. */
    private boolean allValuesHaveLocalContext(List<String> values, String claimText,
                                              Set<String> claimTerms, String evidence,
                                              boolean requireTermOverlap) {
        return values.stream().allMatch(value -> valueHasLocalContext(value, claimTerms, evidence, requireTermOverlap)
            || percentageHasLocalContext(value, claimText, claimTerms, evidence, requireTermOverlap));
    }

    private boolean hasAnyValueWithLocalContext(List<String> values, String claimText,
                                                Set<String> claimTerms, String evidence,
                                                boolean requireTermOverlap) {
        return values.stream().anyMatch(value -> valueHasLocalContext(value, claimTerms, evidence, requireTermOverlap)
            || percentageHasLocalContext(value, claimText, claimTerms, evidence, requireTermOverlap));
    }

    private boolean percentageHasLocalContext(String value, String claimText,
                                              Set<String> claimTerms, String evidence,
                                              boolean requireTermOverlap) {
        if (value == null || !value.endsWith("%")) return false;
        Double percentage = decimal(value.substring(0, value.length() - 1));
        if (percentage == null) return false;
        List<String> operands = matchedValues(claimText).stream()
            .filter(candidate -> !candidate.endsWith("%"))
            .distinct()
            .toList();
        for (String numeratorText : operands) {
            Double numerator = decimal(numeratorText);
            if (numerator == null
                || !valueHasLocalContext(numeratorText, claimTerms, evidence, requireTermOverlap)) continue;
            for (String denominatorText : operands) {
                Double denominator = decimal(denominatorText);
                if (denominator == null || denominator == 0.0
                    || !valueHasLocalContext(denominatorText, claimTerms, evidence, requireTermOverlap)) continue;
                if (Math.abs((numerator / denominator * 100.0) - percentage) < 0.0001) return true;
            }
        }
        if (requireTermOverlap && claimTerms.stream().noneMatch(terms(evidence)::contains)) return false;
        return structuredPercentageSupported(percentage, evidence);
    }

    /**
     * Verifies a displayed percentage from generic structured metric roles. A consumed/used/allocated
     * value may be divided by an explicit total/capacity value, or by consumed + available. Field
     * names and values come from the current evidence; no product or business metric is enumerated.
     */
    private boolean structuredPercentageSupported(double percentage, String evidence) {
        Map<String, Double> fields = new LinkedHashMap<>();
        Matcher matcher = STRUCTURED_NUMERIC_FIELD.matcher(evidence == null ? "" : evidence);
        while (matcher.find()) {
            Double number = decimal(matcher.group(2));
            if (number != null) fields.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), number);
        }
        for (Map.Entry<String, Double> numerator : fields.entrySet()) {
            if (!numerator.getKey().matches(".*(?:used|allocated|consumed|occupied).*")) continue;
            String family = numerator.getKey().replaceAll("(?:used|allocated|consumed|occupied)", "");
            for (Map.Entry<String, Double> denominator : fields.entrySet()) {
                if (denominator.getValue() <= 0.0) continue;
                boolean explicitTotal = denominator.getKey().matches(".*(?:total|capacity|max).*" );
                boolean available = denominator.getKey().matches(".*(?:available|free|remaining).*" );
                if ((!explicitTotal && !available)
                    || (!family.isBlank() && !denominator.getKey().contains(family))) continue;
                double base = explicitTotal ? denominator.getValue()
                    : numerator.getValue() + denominator.getValue();
                if (base > 0.0
                    && Math.abs((numerator.getValue() / base * 100.0) - percentage) < 0.0001) return true;
            }
        }
        return false;
    }

    private Double decimal(String value) {
        try {
            return Double.parseDouble(value == null ? "" : value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean valueHasLocalContext(String value, Set<String> claimTerms, String evidence,
                                         boolean requireTermOverlap) {
        String expected = normalizedComparable(value);
        if (expected.isBlank()) return false;
        for (String line : (evidence == null ? "" : evidence).split("\\R")) {
            if (!normalizedComparable(line).contains(expected)) continue;
            Set<String> lineTerms = terms(line);
            if (!requireTermOverlap || claimTerms.stream().anyMatch(lineTerms::contains)) return true;
        }
        return false;
    }

    private List<String> matchedValues(String value) {
        List<String> values = new ArrayList<>();
        Matcher matcher = NUMBER_OR_DATE.matcher(value == null ? "" : value);
        while (matcher.find()) values.add(matcher.group());
        return List.copyOf(values);
    }

    private Set<String> terms(String value) {
        Set<String> values = new LinkedHashSet<>();
        String tokenizable = (value == null ? "" : value)
            .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
            .toLowerCase(Locale.ROOT);
        Matcher matcher = WORD.matcher(tokenizable);
        while (matcher.find()) {
            String term = matcher.group();
            if (term.codePoints().allMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN)) {
                for (int index = 0; index < term.length() - 1; index++) values.add(term.substring(index, index + 2));
            } else if (!Set.of("the", "and", "with", "from", "this", "that").contains(term)) {
                values.add(term);
                if (term.length() > 4 && term.endsWith("s") && !term.endsWith("ss")) {
                    values.add(term.substring(0, term.length() - 1));
                }
            }
        }
        return values;
    }

    private Set<String> refs(String value) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher matcher = EVIDENCE_REF.matcher(value == null ? "" : value);
        while (matcher.find()) refs.add(cleanRef(matcher.group()));
        return refs;
    }

    private boolean isMaterial(String value) {
        if (value == null || value.length() < 8) return false;
        if (value.startsWith("|") || value.startsWith(">")
            || value.contains("以下为本次工具调用证明")
            || value.contains("完整结构化结果保留在运行元数据中")
            || (value.contains("证据类型") && value.contains("摘要="))
            || value.contains("不作为事实依据")) {
            return false;
        }
        if (isNormativeGuidance(value)
            || value.matches("^\\s*\\*\\*[^*]+\\*\\*\\s*[-—:].*")
            || value.matches("^(?:如需|若需|如果需要|for further|if needed).*")
            || value.matches("^⚠️?.*(?:注意事项|风险提示)[:：]?$")) {
            return false;
        }
        String normalized = value.replaceAll("[`*_>#]", "").trim();
        if (normalized.length() < 8 || normalized.matches("^(证据|参考|来源|说明|结论|风险|限制)[:：]?$")) return false;
        // Evidence-audit coverage is produced by this ledger/runtime metadata itself. Requiring
        // returned business evidence to prove the audit score would create a circular contract.
        if (normalized.matches(".*(?:证据覆盖率|诊断覆盖率|evidence coverage).*")) return false;
        if (normalized.matches("^(?:若|如|如果|当|when|if).*[：:]$")) return false;
        if (normalized.matches("^.*(?:可能原因|原因分析|原因判断)[:：]$")) return false;
        if (normalized.matches("^.*(?:操作提醒|风险提醒|注意事项|安全提醒)[:：]?$")) return false;
        return normalized.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private boolean strongClaim(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return text.matches(".*(必须|确定|证明|导致|属于|符合|不符合|完成|成功|失败|增长|下降|最高|最低|"
            + "must|proves?|causes?|always|never).*" );
    }

    private boolean isNormativeGuidance(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (text.matches(".*(?:建议|请|务必|避免|禁止|不要|应当|应该|先备份|可考虑|可适当|可选择).*")) return true;
        return text.matches(".*(建议|应当|应该|需要|无需|必须|避免|严禁|不要|可清理|先检查|再启动|"
            + "recommend|should|must|avoid|do not|never).*");
    }

    private boolean isNonFactualSectionHeading(String value) {
        String heading = (value == null ? "" : value).replaceFirst("^\\s*#{1,6}\\s*", "")
            .replaceAll("[`*_]", "").trim().toLowerCase(Locale.ROOT);
        return heading.matches("^(?:排查步骤|排查建议|验证命令|修复建议|建议|风险提醒|注意事项|后续行动|"
            + "工具执行证据|证据引用|引用|来源|参考来源|覆盖与限制说明|边界说明|数据覆盖说明)(?:\\s|$|[:：]).*")
            || heading.matches("^(?:troubleshooting|validation commands?|recommendations?|next steps?|risks?|"
            + "references?|sources?|tool evidence|coverage and limitations?|limitations?)(?:\\s|$|:).*");
    }

    private EvidenceItem evidence(String ref, String type, String content, String toolName, boolean trusted) {
        String normalized = content == null ? "" : content.trim();
        return new EvidenceItem(ref, firstNonBlank(type, "UNKNOWN"), toolName == null ? "" : toolName,
            trusted ? "TRUSTED" : "UNAVAILABLE", normalized, sha256(normalized));
    }

    private String evidenceType(String ref) {
        if (ref == null) return "UNKNOWN";
        if (ref.startsWith("doc://")) return "DOCUMENT";
        if (ref.startsWith("web://") || ref.startsWith("http")) return "WEB";
        if (ref.startsWith("tool://")) return "TOOL";
        return "UNKNOWN";
    }

    private String cleanRef(String value) {
        return value == null ? "" : value.replaceAll("[。；，！？;,.!?）)\\]\\\"'`]+$", "").trim();
    }

    private String firstText(Map<String, Object> values, String... keys) {
        if (values == null) return "";
        for (String key : keys) {
            String value = text(values.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String normalizedComparable(String value) {
        return (value == null ? "" : value).toLowerCase(Locale.ROOT).replace(",", "").replaceAll("\\s+", "");
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? (value == null ? "" : value) : value.substring(0, max);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String stableText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record Result(Map<String, Object> claimLedger, Map<String, Object> evidenceManifest,
                  String status, double coverage, int criticalUnboundClaims, int unknownReferences) {
        String reviewerContext() {
            return "Answer evidence preflight (contractVersion=" + CLAIM_LEDGER_VERSION + "): status=" + status
                + ", coverage=" + coverage + ", criticalUnboundClaims=" + criticalUnboundClaims
                + ", unknownReferences=" + unknownReferences
                + ". Reject unsupported high-risk claims and unknown citations; require each numeric, date, causal, "
                + "or definitive claim to carry a returned evidence reference near that claim.";
        }
    }

    public record BindingResult(String answer, int boundClaimCount) {
    }

    private record LineBinding(String text, int boundCount) {
    }

    private record LegacyReferenceNormalization(String answer, int replacementCount) {
    }

    private record EvidenceItem(String evidenceId, String type, String toolName, String trustStatus,
                                String contentPreview, String contentHash) {
        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("evidenceId", evidenceId);
            value.put("type", type);
            value.put("toolName", toolName);
            value.put("trustStatus", trustStatus);
            value.put("contentPreview", contentPreview);
            value.put("contentHash", contentHash);
            return Map.copyOf(value);
        }
    }
}
