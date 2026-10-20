package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.protocol.ModelProtocolJson;
import java.util.*;

/** Mechanical composition of independent, already delivered requests; no analytical decisions. */
final class CodingRequestBatch {
    private CodingRequestBatch() { }

    static List<Map<String,Object>> compose(List<Map<String,Object>> requests, Set<String> delivered) {
        if (requests.size() < 2 || requests.size() > 4) return requests;
        var refs = new LinkedHashSet<String>();
        var ids = new HashSet<String>();
        var fields = new LinkedHashMap<String,Object>();
        var objectives = new ArrayList<Map<String,Object>>();
        for (var request : requests) {
            if (!"RUN_CODING".equals(request.get("operation"))
                    || !Set.of("operation","requestId","datasetReference","datasetReferences","instruction","outputSchema").containsAll(request.keySet())
                    || !(request.get("requestId") instanceof String id) || id.isBlank() || id.length() > 128 || !ids.add(id)
                    || !(request.get("instruction") instanceof String goal) || goal.isBlank() || goal.length() > 16000
                    || !(request.get("outputSchema") instanceof Map<?,?> schema) || !"object".equals(schema.get("type"))) return requests;
            var selected = new ArrayList<String>();
            if (request.get("datasetReference") instanceof String ref && !request.containsKey("datasetReferences")) selected.add(ref);
            else if (!request.containsKey("datasetReference") && request.get("datasetReferences") instanceof List<?> values) {
                for (Object value : values) { if (!(value instanceof String ref)) return requests; selected.add(ref); }
            } else return requests;
            if (selected.isEmpty() || selected.size() > 8 || new HashSet<>(selected).size() != selected.size()
                    || !delivered.containsAll(selected)) return requests;
            refs.addAll(selected);
            String field = "result_" + (fields.size() + 1);
            fields.put(field, Map.of("type","array","items",schema));
            objectives.add(Map.of("resultField",field,"requestId",id,"datasetReferences",selected,"instruction",goal));
        }
        if (refs.size() > 8) return requests;
        String instruction = "Execute these independent agent-authored analytical requests together against the authorized input set. "
            + "Load each input once and share parsing inside this program. Preserve each request's source scope, objective and row schema. "
            + "Write ONE JSONL row: each result_N field is an array of rows matching that request's original outputSchema. "
            + "Do not invent dependencies or change business formulas. Request mapping: " + ModelProtocolJson.compact(objectives);
        if (instruction.length() > 16000) return requests;
        return List.of(Map.of("operation","RUN_CODING", "requestId","batch-"+ModelProtocolJson.sha256Hex(requests),
            "datasetReferences",List.copyOf(refs), "instruction",instruction,
            "outputSchema",Map.of("type","object","properties",fields,"required",List.copyOf(fields.keySet()),"additionalProperties",false)));
    }
}
