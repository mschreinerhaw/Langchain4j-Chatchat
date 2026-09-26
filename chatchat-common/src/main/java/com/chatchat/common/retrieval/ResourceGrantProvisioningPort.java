package com.chatchat.common.retrieval;

/** Creates durable database authorization relationships when a resource is created or adopted. */
public interface ResourceGrantProvisioningPort {

    void ensureUserOwnerGrant(String resourceType, String tenantId, String resourceId, String userId);
}
