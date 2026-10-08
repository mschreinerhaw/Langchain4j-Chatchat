package com.chatchat.mcpserver.datacapability.definition;

import com.chatchat.mcpserver.datacapability.execution.CapabilityAdapter;
import com.chatchat.mcpserver.database.category.DataQueryCategoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class CapabilityService {
    private final CapabilityRepository repository;
    private final ObjectMapper json;
    private final DataQueryCategoryService categories;
    private final Map<CapabilityType, CapabilityAdapter> adapters = new EnumMap<>(CapabilityType.class);

    public CapabilityService(CapabilityRepository repository, ObjectMapper json, List<CapabilityAdapter> adapters, DataQueryCategoryService categories) {
        this.repository = repository;
        this.json = json;
        this.categories = categories;
        for (CapabilityAdapter adapter : adapters) {
            if (this.adapters.put(adapter.type(), adapter) != null) throw new IllegalStateException("Duplicate adapter");
        }
    }

    public List<CapabilityDefinition> list(CapabilityType type) {
        return repository.findAll().stream().filter(e -> type == null || e.getType() == type)
            .map(this::decode).sorted(Comparator.comparing(CapabilityDefinition::code)).toList();
    }

    public CapabilityDefinition get(String code) {
        return decode(repository.findById(code).orElseThrow(() -> new IllegalArgumentException("Capability not found: " + code)));
    }

    public CapabilityAdapter adapter(CapabilityType type) {
        CapabilityAdapter adapter = adapters.get(type);
        if (adapter == null) throw new IllegalArgumentException("Unsupported capability type: " + type);
        return adapter;
    }

    public void validate(CapabilityDefinition d) {
        if (d == null || d.code() == null || !d.code().matches("[a-z][a-z0-9_]{1,99}"))
            throw new IllegalArgumentException("code must contain 2-100 lowercase letters, numbers or underscores");
        if (d.title() == null || d.title().isBlank() || d.title().length() > 200)
            throw new IllegalArgumentException("A title of at most 200 characters is required");
        if (d.timeoutSeconds() < 1 || d.timeoutSeconds() > 300 || d.maxRows() < 1 || d.maxRows() > 10000)
            throw new IllegalArgumentException("timeoutSeconds must be 1-300 and maxRows 1-10000");
        if (!"object".equals(d.inputSchema().get("type")) || !(d.inputSchema().get("properties") instanceof Map))
            throw new IllegalArgumentException("inputSchema must declare an object and properties");
        Map<?, ?> properties = (Map<?, ?>) d.inputSchema().get("properties");
        Object required = d.inputSchema().get("required");
        if (required != null && !(required instanceof List)) throw new IllegalArgumentException("required must be an array");
        if (required instanceof List<?> names && !properties.keySet().containsAll(names))
            throw new IllegalArgumentException("Required parameters must be declared in properties");
        for (var entry : d.resultMapping().entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank())
                throw new IllegalArgumentException("resultMapping requires nonempty output names and source fields");
        }
        if (d.categoryId() != null && !d.categoryId().isBlank()) categories.require(d.categoryId());
        adapter(d.type()).validate(d);
    }

    @Transactional
    public CapabilityDefinition create(CapabilityDefinition definition) {
        validate(definition);
        if (repository.existsById(definition.code())) throw new IllegalArgumentException("Duplicate capability code");
        return save(definition);
    }

    @Transactional
    public CapabilityDefinition update(String code, CapabilityDefinition definition) {
        if (!code.equals(definition.code())) throw new IllegalArgumentException("Capability code cannot change");
        get(code);
        return save(definition);
    }

    private CapabilityDefinition save(CapabilityDefinition d) {
        validate(d);
        CapabilityEntity entity = new CapabilityEntity();
        entity.setCode(d.code()); entity.setType(d.type()); entity.setUpdatedAt(Instant.now());
        try { entity.setDefinitionJson(json.writeValueAsString(d)); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid capability definition", ex); }
        repository.saveAndFlush(entity);
        return d;
    }

    @Transactional
    public void delete(String code) { get(code); repository.deleteById(code); repository.flush(); }

    private CapabilityDefinition decode(CapabilityEntity e) {
        try { return json.readValue(e.getDefinitionJson(), CapabilityDefinition.class); }
        catch (Exception ex) { throw new IllegalStateException("Cannot read capability " + e.getCode(), ex); }
    }
}
