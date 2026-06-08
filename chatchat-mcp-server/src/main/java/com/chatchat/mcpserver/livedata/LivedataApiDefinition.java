package com.chatchat.mcpserver.livedata;

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
    String releaseVersion
) {
}
