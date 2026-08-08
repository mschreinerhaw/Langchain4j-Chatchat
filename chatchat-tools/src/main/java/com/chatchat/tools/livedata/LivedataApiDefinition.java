package com.chatchat.tools.livedata;

public record LivedataApiDefinition(
    String id,
    String apiId,
    String apiName,
    String params,
    String description,
    String namespace,
    String serviceName,
    String methodName,
    Integer state,
    String version,
    String releaseVersion,
    String responseColumns
) {
    public LivedataApiDefinition(
        String id,
        String apiId,
        String apiName,
        String params,
        String description,
        String namespace,
        String serviceName,
        String methodName,
        Integer state,
        String version,
        String releaseVersion
    ) {
        this(id, apiId, apiName, params, description, namespace, serviceName,
            methodName, state, version, releaseVersion, null);
    }
}
