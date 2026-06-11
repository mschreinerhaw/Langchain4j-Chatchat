package com.chatchat.api.exception;

/**
 * Exception thrown when a requested resource is not found
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;
    private final String resourceId;

    /**
     * Creates a new ResourceNotFoundException instance.
     *
     * @param message the message value
     */
    public ResourceNotFoundException(String message) {
        this(message, null, null);
    }

    /**
     * Creates a new ResourceNotFoundException instance.
     *
     * @param message the message value
     * @param resourceType the resource type value
     * @param resourceId the resource id value
     */
    public ResourceNotFoundException(String message, String resourceType, String resourceId) {
        super(message);
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    /**
     * Returns the resource type.
     *
     * @return the resource type
     */
    public String getResourceType() {
        return resourceType;
    }

    /**
     * Returns the resource id.
     *
     * @return the resource id
     */
    public String getResourceId() {
        return resourceId;
    }

    /**
     * Create a ResourceNotFoundException for a specific resource
     */
    public static ResourceNotFoundException forResource(String resourceType, String resourceId) {
        return new ResourceNotFoundException(
            String.format("%s with id '%s' not found", resourceType, resourceId),
            resourceType,
            resourceId
        );
    }
}
