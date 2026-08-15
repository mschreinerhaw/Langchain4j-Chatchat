package com.chatchat.mcpserver.market;

import com.chatchat.runtime.market.storage.FinancialReadOperations;
import com.chatchat.runtime.market.storage.FinancialSnapshotPublisher;
import com.chatchat.runtime.market.storage.FinancialWriteStorage;
import com.zaxxer.hikari.HikariConfig;
import org.h2.tools.Restore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Builds physical H2 read replicas and atomically swaps the online query pool. */
public final class SnapshotFinancialReadOperations
    implements FinancialReadOperations, FinancialSnapshotPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SnapshotFinancialReadOperations.class);
    private final FinancialWriteStorage writer;
    private final FinancialQueryPoolProperties properties;
    private final ReentrantReadWriteLock switchLock = new ReentrantReadWriteLock(true);
    private FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations active;
    private volatile String activeSlot;
    private volatile long generation;

    public SnapshotFinancialReadOperations(FinancialWriteStorage writer,
                                           FinancialQueryPoolProperties properties) {
        this.writer = writer;
        this.properties = properties;
    }

    @Override
    public synchronized void publish() {
        String targetSlot = "A".equals(activeSlot) ? "B" : "A";
        Path root = safeRoot();
        Path targetDirectory = root.resolve("read-" + targetSlot.toLowerCase(java.util.Locale.ROOT)).normalize();
        Path backup = root.resolve("writer-online-backup.zip").normalize();
        FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations candidate = null;
        try {
            Files.createDirectories(root);
            Files.deleteIfExists(backup);
            String backupPath = backup.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
            writer.jdbc().execute("BACKUP TO '" + backupPath + "'");
            recreateDirectory(root, targetDirectory);
            Restore.execute(backup.toString(), targetDirectory.toString(), properties.getSnapshotDatabaseName());
            candidate = openCandidate(targetSlot);
            candidate.queryForList("select count(*) as catalog_count from market_asset_catalog");

            FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations previous;
            switchLock.writeLock().lock();
            try {
                previous = active;
                active = candidate;
                candidate = null;
                activeSlot = targetSlot;
                generation++;
            } finally {
                switchLock.writeLock().unlock();
            }
            if (previous != null) previous.close();
            log.info("Financial H2 read snapshot activated generation={} slot={} directory={}",
                generation, activeSlot, targetDirectory);
        } catch (Exception ex) {
            if (candidate != null) candidate.close();
            throw new IllegalStateException("Failed to publish financial H2 read snapshot", ex);
        } finally {
            try { Files.deleteIfExists(backup); } catch (Exception ignored) { }
        }
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql, Object... arguments) {
        switchLock.readLock().lock();
        try {
            return active().queryForList(sql, arguments);
        } finally {
            switchLock.readLock().unlock();
        }
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql, Map<String, Object> parameters,
                                                  int maxRows, int timeoutSeconds) {
        switchLock.readLock().lock();
        try {
            return active().queryForList(sql, parameters, maxRows, timeoutSeconds);
        } finally {
            switchLock.readLock().unlock();
        }
    }

    public String activeSlot() {
        return activeSlot;
    }

    public long generation() {
        return generation;
    }

    private FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations active() {
        if (active == null) throw new IllegalStateException("Financial read snapshot is not initialized");
        return active;
    }

    private FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations openCandidate(String slot) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("FinancialSnapshotQueryPool-" + slot);
        config.setJdbcUrl(properties.snapshotJdbcUrl(slot));
        config.setUsername(properties.getLocalUsername());
        config.setPassword(properties.getLocalPassword());
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(Math.max(1, Math.min(16, properties.getMaximumPoolSize())));
        config.setMinimumIdle(Math.max(0, Math.min(config.getMaximumPoolSize(), properties.getMinimumIdle())));
        config.setConnectionTimeout(Math.max(250L, properties.getConnectionTimeoutMs()));
        config.setValidationTimeout(Math.max(250L, properties.getValidationTimeoutMs()));
        config.setIdleTimeout(Math.max(10_000L, properties.getIdleTimeoutMs()));
        config.setMaxLifetime(Math.max(30_000L, properties.getMaxLifetimeMs()));
        config.setReadOnly(true);
        return new FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations(
            config, properties.getQueryTimeoutSeconds());
    }

    private Path safeRoot() {
        String configured = properties.getSnapshotRoot();
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("Financial snapshot root cannot be blank");
        }
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (root.getParent() == null || root.getNameCount() < 2) {
            throw new IllegalArgumentException("Financial snapshot root is too broad: " + root);
        }
        return root;
    }

    private void recreateDirectory(Path root, Path target) throws Exception {
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Unsafe financial snapshot target: " + target);
        }
        if (Files.exists(target)) {
            try (var paths = Files.walk(target)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        Files.createDirectories(target);
    }

    @Override
    public synchronized void close() {
        switchLock.writeLock().lock();
        try {
            if (active != null) active.close();
            active = null;
        } finally {
            switchLock.writeLock().unlock();
        }
    }
}
