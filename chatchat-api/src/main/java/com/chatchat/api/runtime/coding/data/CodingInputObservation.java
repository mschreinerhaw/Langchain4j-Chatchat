package com.chatchat.api.runtime.coding.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Explicit task-local data observation, not stdout forwarding or an analytical verdict. */
public final class CodingInputObservation {
    public static final String POLICY_REF = "runtime-coding:bounded-input-observation:v1";
    private final ObjectMapper json;
    private final String source;
    private final boolean batched;
    private final Set<String> observed = new HashSet<>();
    private final List<Map<String,Object>> samples = new ArrayList<>();
    private int bytes=2; // JSON array brackets.
    CodingInputObservation(ObjectMapper json, String source, boolean batched) {
        this.json=json; this.source=source; this.batched=batched;
    }
    void accept(Map<String,Object> row) {
        String ref=batched && row.get("datasetReference") instanceof String value ? value : source;
        if (observed.size()>=8 || !observed.add(ref)) return;
        try {
            String value=json.writeValueAsString(row);
            String prefix=value.codePoints().limit(1024).collect(StringBuilder::new,
                StringBuilder::appendCodePoint,StringBuilder::append).toString();
            var sample=Map.<String,Object>of("datasetReference",ref,"rowJsonPrefix",prefix,
                "truncated",prefix.length()!=value.length());
            int size=json.writeValueAsBytes(sample).length;
            int separator=samples.isEmpty()?0:1;
            if (bytes+size+separator>16384) return;
            samples.add(sample);bytes+=size+separator;
        } catch (java.io.IOException invalid) { throw new IllegalStateException("Input observation cannot be encoded",invalid); }
    }
    Map<String,Object> snapshot() {
        return Map.of("schemaVersion","runtime_os.coding.input-observation.v1","policyRef",POLICY_REF,
            "purpose","FORMAT_ORIENTATION_ONLY_NOT_COMPLETE_ANALYTICAL_EVIDENCE",
            "complete",false,"maxSources",8,"maxRowPrefixCodePoints",1024,"maxSampleBytes",16384,
            "samples",List.copyOf(samples));
    }
}
