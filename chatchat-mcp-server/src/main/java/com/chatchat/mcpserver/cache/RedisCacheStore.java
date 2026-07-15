package com.chatchat.mcpserver.cache;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheStore {

    private static final byte[] INDEX_KEY = "db-query-cache:index".getBytes(StandardCharsets.UTF_8);

    private final RedisCacheConfigService configService;
    private volatile LettuceConnectionFactory connectionFactory;
    private volatile String fingerprint = "";

    public boolean isUsable() {
        try {
            RedisCacheConfig config = configService.current();
            return config.isEnabled() && ensureFactory(config) != null;
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean isConnected() {
        if (!isUsable()) return false;
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.commands().ping());
        } catch (Exception ex) {
            return false;
        }
    }

    public byte[] get(String key) {
        RedisCacheConfig config = requiredConfig();
        byte[] rawKey = bytes(key);
        try (RedisConnection connection = ensureFactory(config).getConnection()) {
            byte[] value = connection.stringCommands().get(rawKey);
            if (value == null) connection.setCommands().sRem(INDEX_KEY, rawKey);
            return value;
        }
    }

    public void put(String key, byte[] value, long ttlSeconds) {
        RedisCacheConfig config = requiredConfig();
        byte[] rawKey = bytes(key);
        try (RedisConnection connection = ensureFactory(config).getConnection()) {
            connection.stringCommands().set(
                rawKey,
                value,
                Expiration.seconds(Math.max(1, ttlSeconds)),
                RedisStringCommands.SetOption.UPSERT
            );
            connection.setCommands().sAdd(INDEX_KEY, rawKey);
        }
    }

    public void delete(String key) {
        delete(bytes(key));
    }

    public void delete(byte[] key) {
        RedisCacheConfig config = requiredConfig();
        try (RedisConnection connection = ensureFactory(config).getConnection()) {
            connection.keyCommands().del(key);
            connection.setCommands().sRem(INDEX_KEY, key);
        }
    }

    public List<McpRocksDbStore.KeyValue> scan(String prefix, int limit) {
        if (limit <= 0 || !isUsable()) return List.of();
        RedisCacheConfig config = requiredConfig();
        List<McpRocksDbStore.KeyValue> entries = new ArrayList<>();
        try (RedisConnection connection = ensureFactory(config).getConnection()) {
            var keys = connection.setCommands().sMembers(INDEX_KEY);
            if (keys == null) return List.of();
            for (byte[] key : keys) {
                if (entries.size() >= limit) break;
                if (!text(key).startsWith(prefix)) continue;
                byte[] value = connection.stringCommands().get(key);
                if (value == null) {
                    connection.setCommands().sRem(INDEX_KEY, key);
                } else {
                    entries.add(new McpRocksDbStore.KeyValue(key, value));
                }
            }
        }
        return entries;
    }

    public String test(RedisCacheConfig config) {
        LettuceConnectionFactory candidate = createFactory(configService.normalize(config));
        try {
            candidate.afterPropertiesSet();
            candidate.start();
            try (RedisConnection connection = candidate.getConnection()) {
                String pong = connection.commands().ping();
                if (!"PONG".equalsIgnoreCase(pong)) {
                    throw new IllegalStateException("Redis did not return PONG");
                }
                return pong;
            }
        } finally {
            candidate.destroy();
        }
    }

    public synchronized void reload() {
        closeFactory();
        fingerprint = "";
    }

    @PreDestroy
    public synchronized void close() {
        closeFactory();
    }

    private RedisCacheConfig requiredConfig() {
        RedisCacheConfig config = configService.current();
        if (!config.isEnabled()) throw new IllegalStateException("Redis cache storage is disabled");
        return config;
    }

    private LettuceConnectionFactory ensureFactory(RedisCacheConfig config) {
        String nextFingerprint = fingerprint(config);
        if (connectionFactory != null && Objects.equals(fingerprint, nextFingerprint)) return connectionFactory;
        synchronized (this) {
            if (connectionFactory != null && Objects.equals(fingerprint, nextFingerprint)) return connectionFactory;
            closeFactory();
            LettuceConnectionFactory created = createFactory(config);
            created.afterPropertiesSet();
            created.start();
            connectionFactory = created;
            fingerprint = nextFingerprint;
            return created;
        }
    }

    private LettuceConnectionFactory createFactory(RedisCacheConfig config) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(config.getTimeoutMillis()));
        if (config.isSsl()) client.useSsl();
        LettuceClientConfiguration clientConfiguration = client.build();
        List<HostPort> nodes = configService.nodes(config).stream().map(this::hostPort).toList();
        return switch (config.getMode()) {
            case "SENTINEL" -> {
                RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration();
                sentinel.master(config.getMasterName());
                nodes.forEach(node -> sentinel.addSentinel(new RedisNode(node.host(), node.port())));
                applyCredentials(sentinel, config);
                if (config.getSentinelUsername() != null && !config.getSentinelUsername().isBlank()) {
                    sentinel.setSentinelUsername(config.getSentinelUsername());
                }
                if (config.getSentinelPassword() != null && !config.getSentinelPassword().isBlank()) {
                    sentinel.setSentinelPassword(RedisPassword.of(config.getSentinelPassword()));
                }
                sentinel.setDatabase(config.getDatabaseIndex());
                yield new LettuceConnectionFactory(sentinel, clientConfiguration);
            }
            case "CLUSTER" -> {
                RedisClusterConfiguration cluster = new RedisClusterConfiguration(
                    nodes.stream().map(node -> node.host() + ":" + node.port()).toList()
                );
                applyCredentials(cluster, config);
                cluster.setMaxRedirects(config.getMaxRedirects());
                yield new LettuceConnectionFactory(cluster, clientConfiguration);
            }
            default -> {
                HostPort node = nodes.get(0);
                RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                    node.host(), node.port()
                );
                standalone.setDatabase(config.getDatabaseIndex());
                if (!"STANDALONE_NO_AUTH".equals(config.getMode())) applyCredentials(standalone, config);
                yield new LettuceConnectionFactory(standalone, clientConfiguration);
            }
        };
    }

    private void applyCredentials(org.springframework.data.redis.connection.RedisConfiguration.WithAuthentication configuration,
                                  RedisCacheConfig config) {
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            configuration.setUsername(config.getUsername());
        }
        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            configuration.setPassword(RedisPassword.of(config.getPassword()));
        }
    }

    private HostPort hostPort(String value) {
        int separator = value.lastIndexOf(':');
        return new HostPort(value.substring(0, separator), Integer.parseInt(value.substring(separator + 1)));
    }

    private String fingerprint(RedisCacheConfig config) {
        return String.join("|",
            config.getMode(), config.getNodesJson(), config.getMasterName(), String.valueOf(config.getDatabaseIndex()),
            config.getUsername(), config.getPassword(), config.getSentinelUsername(), config.getSentinelPassword(), String.valueOf(config.isSsl()),
            String.valueOf(config.getTimeoutMillis()), String.valueOf(config.getMaxRedirects())
        );
    }

    private void closeFactory() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
            connectionFactory = null;
        }
    }

    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }

    private record HostPort(String host, int port) {
    }
}
