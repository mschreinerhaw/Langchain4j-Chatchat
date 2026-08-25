package com.chatchat.common.bridge.api;

/** Business outcome inside a successful bridge exchange. */
public enum McpApiResultStatus {
    SUCCESS,
    EMPTY,
    RESOLUTION_REQUIRED,
    REJECTED,
    FAILED
}
