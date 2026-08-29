package com.chatchat.agents.orchestration.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * Protocol boundary for parsing model-produced planner envelopes.
 *
 * <p>Only invalid characters inside JSON strings are repaired. Structural
 * corruption such as missing delimiters remains rejected, so protocol
 * tolerance cannot silently bypass planner validation.</p>
 */
@Slf4j
public final class PlannerEnvelopeParser {

    private final ObjectMapper objectMapper;

    public PlannerEnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PlannerEnvelopeDto parse(String json) throws IOException {
        try {
            return PlannerEnvelopeDto.from(objectMapper.readTree(json), objectMapper);
        } catch (JsonProcessingException initialFailure) {
            String repaired = repairInvalidStringCharacters(json);
            if (repaired.equals(json)) {
                throw initialFailure;
            }
            PlannerEnvelopeDto parsed = PlannerEnvelopeDto.from(objectMapper.readTree(repaired), objectMapper);
            log.warn("Planner JSON contained unescaped quote or control characters inside string values; "
                + "Runtime repaired the JSON syntax before InterpretationPlan validation.");
            return parsed;
        }
    }

    String repairInvalidStringCharacters(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        StringBuilder repaired = new StringBuilder(json.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        boolean changed = false;
        for (int index = 0; index < json.length(); index++) {
            char current = json.charAt(index);
            if (!inString) {
                repaired.append(current);
                if (current == '"') {
                    inString = true;
                }
                continue;
            }
            if (escaped) {
                repaired.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                repaired.append(current);
                escaped = true;
                continue;
            }
            if (current < 0x20) {
                appendControlCharacter(repaired, current);
                changed = true;
                continue;
            }
            if (current != '"') {
                repaired.append(current);
                continue;
            }
            int next = index + 1;
            while (next < json.length() && Character.isWhitespace(json.charAt(next))) {
                next++;
            }
            boolean legalTerminator = next >= json.length()
                || json.charAt(next) == ':'
                || json.charAt(next) == ','
                || json.charAt(next) == '}'
                || json.charAt(next) == ']';
            if (legalTerminator) {
                repaired.append(current);
                inString = false;
            } else {
                repaired.append('\\').append(current);
                changed = true;
            }
        }
        return changed ? repaired.toString() : json;
    }

    private void appendControlCharacter(StringBuilder target, char value) {
        switch (value) {
            case '\b' -> target.append("\\b");
            case '\f' -> target.append("\\f");
            case '\n' -> target.append("\\n");
            case '\r' -> target.append("\\r");
            case '\t' -> target.append("\\t");
            default -> {
                String hex = Integer.toHexString(value);
                target.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
            }
        }
    }
}
