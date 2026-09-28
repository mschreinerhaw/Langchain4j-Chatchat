package com.chatchat.runtime.skill.api.execution;

import java.util.*;
import java.util.function.Supplier;

/** Request-local cache. Hosts must reauthorize a binding before looking it up. Never supplied by HTTP. */
public final class SkillDataSession {
    private final Map<List<Object>, SkillDataResult> results = new LinkedHashMap<>();
    public SkillDataResult acquire(List<Object> identity, Supplier<SkillDataResult> load) {
        List<Object> key = List.copyOf(identity);
        SkillDataResult existing = results.get(key);
        if (existing != null) return existing;
        SkillDataResult result = load.get();
        if (results.size() < 64 && (result.status() == SkillDataResult.Status.AVAILABLE || result.status() == SkillDataResult.Status.EMPTY))
            results.put(key, result);
        return result;
    }
}
