package com.chatchat.common.runtime.agent;

import java.util.Optional;

/** Secret boundary for Agent Gateway adapters. Registry records contain references only. */
public interface AgentCredentialResolver {
    Optional<String> resolveBearerToken(String credentialRef);
}
