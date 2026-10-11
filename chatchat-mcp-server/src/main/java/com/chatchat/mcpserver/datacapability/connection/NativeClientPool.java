package com.chatchat.mcpserver.datacapability.connection;

import java.util.*;
import java.util.function.Supplier;

/** Bounded, leased clients: replacement never closes a connection still used by a query. */
final class NativeClientPool<T extends AutoCloseable> implements AutoCloseable {
    private final int capacity;
    private final Map<String, Entry<T>> entries = new LinkedHashMap<>(16, .75f, true);
    private boolean closed;
    private int liveClients;
    NativeClientPool(int capacity) { this.capacity = capacity; }

    synchronized Lease<T> acquire(String asset, String fingerprint, Supplier<T> factory) {
        if (closed) throw new IllegalStateException("Native database clients are shutting down");
        Entry<T> entry = entries.get(asset);
        if (entry != null && !entry.fingerprint.equals(fingerprint)) {
            entries.remove(asset); retire(entry); entry = null;
        }
        if (entry == null) {
            if (liveClients >= capacity) {
                var idle = entries.entrySet().stream().filter(e -> e.getValue().users == 0).findFirst();
                if (idle.isEmpty()) throw new IllegalStateException("Native database client capacity exhausted");
                retire(entries.remove(idle.get().getKey()));
            }
            if (liveClients >= capacity) throw new IllegalStateException("Native database client capacity exhausted");
            entry = new Entry<>(fingerprint, factory.get()); liveClients++; entries.put(asset, entry);
        }
        entry.users++;
        return new Lease<>(this, entry);
    }
    private synchronized void release(Entry<T> entry) {
        entry.users--;
        if (entry.retired && entry.users == 0) dispose(entry.client);
    }
    private void retire(Entry<T> entry) {
        entry.retired = true;
        if (entry.users == 0) dispose(entry.client);
    }
    private void dispose(T client) {
        liveClients--;
        try { client.close(); } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(NativeClientPool.class).warn("Native database client close failed", ex);
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; entries.values().forEach(this::retire); entries.clear();
    }
    private static final class Entry<T> {
        final String fingerprint; final T client;
        int users; boolean retired;
        Entry(String fingerprint, T client) { this.fingerprint = fingerprint; this.client = client; }
    }
    static final class Lease<T extends AutoCloseable> implements AutoCloseable {
        private final NativeClientPool<T> owner;
        private final Entry<T> entry;
        private boolean released;
        Lease(NativeClientPool<T> owner, Entry<T> entry) { this.owner = owner; this.entry = entry; }
        T client() { return entry.client; }
        @Override public synchronized void close() { if (!released) { released = true; owner.release(entry); } }
    }
}
