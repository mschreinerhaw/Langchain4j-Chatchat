package com.chatchat.integration.agent;

import com.chatchat.common.runtime.agent.AgentCredentialResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Minimal secret adapter. Production deployments can replace it with Vault/KMS. */
@Component
public class EnvironmentAgentCredentialResolver implements AgentCredentialResolver {
    private static final String PREFIX = "env:";

    @Override
    public Optional<String> resolveBearerToken(String credentialRef) {
        if (credentialRef == null || !credentialRef.startsWith(PREFIX)) return Optional.empty();
        String name = credentialRef.substring(PREFIX.length()).trim();
        if (name.isEmpty()) return Optional.empty();
        return Optional.ofNullable(System.getenv(name)).filter(value -> !value.isBlank());
    }
}
