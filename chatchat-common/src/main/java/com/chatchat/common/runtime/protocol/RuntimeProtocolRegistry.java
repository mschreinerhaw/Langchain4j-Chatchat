package com.chatchat.common.runtime.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** Typed composition boundary for replaceable Runtime OS protocol implementations. */
public final class RuntimeProtocolRegistry {
    private final Map<Class<?>, RuntimeProtocolPort> ports;

    private RuntimeProtocolRegistry(Map<Class<?>, RuntimeProtocolPort> ports) {
        this.ports = Map.copyOf(ports);
    }

    public static Builder builder() { return new Builder(); }

    public <P extends RuntimeProtocolPort> P require(Class<P> protocolType) {
        RuntimeProtocolPort port = ports.get(protocolType);
        if (port == null) throw new IllegalStateException("Runtime protocol is not registered: " + protocolType.getName());
        return protocolType.cast(port);
    }

    public Map<Class<?>, RuntimeProtocolPort> ports() { return ports; }

    public static final class Builder {
        private final Map<Class<?>, RuntimeProtocolPort> ports = new LinkedHashMap<>();

        public <P extends RuntimeProtocolPort> Builder register(Class<P> protocolType, P implementation) {
            if (protocolType == null || implementation == null) {
                throw new IllegalArgumentException("Runtime protocol type and implementation are required");
            }
            if (!protocolType.isInterface() || !protocolType.isInstance(implementation)) {
                throw new IllegalArgumentException("Implementation does not satisfy protocol " + protocolType.getName());
            }
            if (ports.putIfAbsent(protocolType, implementation) != null) {
                throw new IllegalStateException("Runtime protocol already registered: " + protocolType.getName());
            }
            return this;
        }

        public RuntimeProtocolRegistry build() { return new RuntimeProtocolRegistry(ports); }
    }
}
