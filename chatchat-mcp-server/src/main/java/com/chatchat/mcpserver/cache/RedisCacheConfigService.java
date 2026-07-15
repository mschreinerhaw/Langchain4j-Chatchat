package com.chatchat.mcpserver.cache;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RedisCacheConfigService {

    private static final Set<String> MODES = Set.of(
        "STANDALONE_NO_AUTH", "STANDALONE_AUTH", "SENTINEL", "CLUSTER"
    );

    private final RedisCacheConfigRepository repository;
    private final ObjectMapper objectMapper;

    public RedisCacheConfig current() {
        return normalize(repository.findById(RedisCacheConfig.DEFAULT_ID).orElseGet(RedisCacheConfig::new));
    }

    @Transactional
    public RedisCacheConfig save(RedisCacheConfig request, boolean preserveEmptyPassword) {
        RedisCacheConfig current = repository.findById(RedisCacheConfig.DEFAULT_ID).orElseGet(RedisCacheConfig::new);
        current.setId(RedisCacheConfig.DEFAULT_ID);
        current.setEnabled(request.isEnabled());
        current.setMode(request.getMode());
        current.setNodesJson(request.getNodesJson());
        current.setMasterName(request.getMasterName());
        current.setDatabaseIndex(request.getDatabaseIndex());
        current.setUsername(request.getUsername());
        if (!preserveEmptyPassword || (request.getPassword() != null && !request.getPassword().isBlank())) {
            current.setPassword(text(request.getPassword()));
        }
        current.setSentinelUsername(request.getSentinelUsername());
        if (!preserveEmptyPassword || (request.getSentinelPassword() != null && !request.getSentinelPassword().isBlank())) {
            current.setSentinelPassword(text(request.getSentinelPassword()));
        }
        current.setSsl(request.isSsl());
        current.setTimeoutMillis(request.getTimeoutMillis());
        current.setMaxRedirects(request.getMaxRedirects());
        return repository.save(normalize(current));
    }

    public List<String> nodes(RedisCacheConfig config) {
        try {
            return objectMapper.readValue(config.getNodesJson(), new TypeReference<List<String>>() {}).stream()
                .map(this::text)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Redis nodes must be a valid JSON string array");
        }
    }

    public RedisCacheConfig normalize(RedisCacheConfig config) {
        String mode = text(config.getMode()).toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) throw new IllegalArgumentException("Unsupported Redis connection mode: " + mode);
        config.setMode(mode);
        List<String> nodes = nodes(config);
        if (nodes.isEmpty()) throw new IllegalArgumentException("Redis node is required");
        nodes.forEach(this::validateNode);
        if (mode.equals("STANDALONE_NO_AUTH") || mode.equals("STANDALONE_AUTH")) {
            nodes = List.of(nodes.get(0));
        }
        if (mode.equals("SENTINEL") && text(config.getMasterName()).isBlank()) {
            throw new IllegalArgumentException("Redis sentinel master name is required");
        }
        if (mode.equals("STANDALONE_AUTH") && text(config.getPassword()).isBlank()) {
            throw new IllegalArgumentException("Redis password is required for authenticated standalone mode");
        }
        if (mode.equals("STANDALONE_NO_AUTH")) {
            config.setUsername("");
            config.setPassword("");
        }
        if (!mode.equals("SENTINEL")) {
            config.setSentinelUsername("");
            config.setSentinelPassword("");
        }
        config.setNodesJson(ModelProtocolJson.compact(nodes));
        config.setMasterName(text(config.getMasterName()));
        config.setUsername(text(config.getUsername()));
        config.setSentinelUsername(text(config.getSentinelUsername()));
        config.setDatabaseIndex(Math.max(0, Math.min(config.getDatabaseIndex(), 15)));
        config.setTimeoutMillis(Math.max(500, Math.min(config.getTimeoutMillis(), 60000)));
        config.setMaxRedirects(Math.max(1, Math.min(config.getMaxRedirects(), 20)));
        return config;
    }

    private void validateNode(String node) {
        int separator = node.lastIndexOf(':');
        if (separator <= 0 || separator == node.length() - 1) {
            throw new IllegalArgumentException("Redis node must use host:port: " + node);
        }
        try {
            int port = Integer.parseInt(node.substring(separator + 1));
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Redis node port is invalid: " + node);
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
