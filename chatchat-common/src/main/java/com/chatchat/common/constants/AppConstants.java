package com.chatchat.common.constants;

/**
 * Application-wide constants for ChatChat
 */
public final class AppConstants {

    // Application info
    public static final String APP_NAME = "ChatChat";
    public static final String APP_VERSION = "1.0.0-SNAPSHOT";

    // API versions
    public static final String API_V1 = "/api/v1";

    // Common HTTP headers
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_X_REQUEST_ID = "X-Request-ID";

    // Response messages
    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";

    // Pagination defaults
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    // Timeout settings (in seconds)
    public static final int DEFAULT_TIMEOUT = 30;
    public static final int STREAMING_TIMEOUT = 300;

    /**
     * Creates a new AppConstants instance.
     */
    private AppConstants() {
        throw new AssertionError("Cannot instantiate constants class");
    }
}
