package com.chatchat.chat.analysis.profile;

import com.chatchat.agents.orchestration.analysis.prompt.DomainAnalysisProfileProvider;
import com.chatchat.agents.orchestration.analysis.prompt.DynamicAnalysisPromptContract;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class DomainAnalysisProfileService implements DomainAnalysisProfileProvider {
    private static final String GLOBAL = "__global__";
    private final DomainAnalysisProfileRepository repository;
    private final ObjectMapper json;

    public record Update(String name, String description, boolean enabled, Long expectedRevision, Map<String, Object> guidance) { }
    public record View(String analysisType, String name, String description, boolean enabled, long revision,
                       String source, Map<String, Object> guidance) { }

    @Transactional(readOnly = true)
    public List<View> list(String tenant) {
        Map<String, DomainAnalysisProfileEntity> merged = new TreeMap<>();
        repository.findByTenantIdOrderByAnalysisType(GLOBAL).forEach(row -> merged.put(row.getAnalysisType(), row));
        repository.findByTenantIdOrderByAnalysisType(tenant(tenant)).forEach(row -> merged.put(row.getAnalysisType(), row));
        return merged.values().stream().map(this::view).toList();
    }

    @Override @Transactional(readOnly = true)
    public List<Profile> profiles(String tenant) {
        return list(tenant).stream().filter(View::enabled).limit(40)
            .map(row -> new Profile(row.analysisType(), row.name(), row.description(), row.revision(), row.guidance())).toList();
    }

    @Transactional
    public View update(String tenant, String analysisType, Update update) {
        String owner = tenant(tenant), type = type(analysisType);
        if (update == null) throw new IllegalArgumentException("profile 不能为空");
        String name = text(update.name(), 120), description = text(update.description(), 600);
        Map<String, Object> guidance = validate(update.guidance());
        var row = repository.findById(owner + ":" + type).orElse(null);
        if (row == null && update.expectedRevision() != null || row != null && !Objects.equals(row.getRevision(), update.expectedRevision())) {
            throw new IllegalArgumentException("配置版本已变更，请重新读取后修改；新增租户配置时 expectedRevision 应为 null");
        }
        if (row == null) { row = new DomainAnalysisProfileEntity(); row.setId(owner + ":" + type); row.setTenantId(owner); row.setAnalysisType(type); }
        row.setName(name); row.setDescription(description); row.setEnabled(update.enabled()); row.setGuidanceJson(write(guidance));
        return view(repository.saveAndFlush(row));
    }

    /** Initial data only: existing database rows (including disabled ones) are never overwritten. */
    @Transactional
    public void initializeDefaults() {
        try (var stream = getClass().getResourceAsStream("/analysis/domain-profile-defaults.json")) {
            List<Map<String, Object>> defaults = json.readValue(stream, new TypeReference<>() { });
            for (var value : defaults) {
                String type = type(String.valueOf(value.get("analysisType"))), id = GLOBAL + ":" + type;
                if (repository.existsById(id)) continue;
                var row = new DomainAnalysisProfileEntity();
                row.setId(id); row.setTenantId(GLOBAL); row.setAnalysisType(type);
                row.setName(text((String) value.get("name"), 120)); row.setDescription(text((String) value.get("description"), 600));
                row.setEnabled(true); row.setGuidanceJson(write(validate(json.convertValue(value.get("guidance"), new TypeReference<Map<String, Object>>() { }))));
                repository.save(row);
            }
        } catch (java.io.IOException invalid) { throw new IllegalStateException("无法加载初始领域 profile", invalid); }
    }

    private Map<String, Object> validate(Map<String, Object> input) {
        if (input == null || input.isEmpty() || write(input).length() > 5000
            || !Set.of("focus", "methodology", "output", "sectionTitles").containsAll(input.keySet())) {
            throw new IllegalArgumentException("profile 只支持 focus、methodology、output、sectionTitles，总长度不超过5000字符");
        }
        Map<String, Object> candidate = new LinkedHashMap<>(input);
        candidate.put("schemaVersion", DynamicAnalysisPromptContract.SCHEMA_VERSION);
        candidate.put("role", Map.of("name", "profile validation"));
        candidate.put("objective", Map.of("goal", "profile validation"));
        Map<String, Object> normalized = DynamicAnalysisPromptContract.from(candidate).toMap();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("focus", "methodology", "output", "sectionTitles")) {
            if (normalized.containsKey(key)) result.put(key, normalized.get(key));
        }
        return result;
    }
    private View view(DomainAnalysisProfileEntity row) {
        try { return new View(row.getAnalysisType(), row.getName(), row.getDescription(), row.isEnabled(),
            row.getRevision() == null ? 0 : row.getRevision(), GLOBAL.equals(row.getTenantId()) ? "GLOBAL" : "TENANT",
            validate(json.readValue(row.getGuidanceJson(), new TypeReference<>() { }))); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("领域 profile 内容无法解析", invalid); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("profile 不是有效JSON", invalid); }
    }
    private String tenant(String value) {
        String tenant = value == null || value.isBlank() ? "default" : value.trim();
        if (tenant.length() > 128 || tenant.equals(GLOBAL) || tenant.contains(":")) throw new IllegalArgumentException("无效租户");
        return tenant;
    }
    private String type(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!type.matches("[A-Z][A-Z0-9_]{0,63}") || type.equals("GENERIC")) throw new IllegalArgumentException("无效分析类型");
        return type;
    }
    private String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) throw new IllegalArgumentException("名称或描述为空或超长");
        return value.trim();
    }
}
