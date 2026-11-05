package com.chatchat.agents.model;

/** Observable transport facts only. A timeout never proves provider reasoning or queueing was slow. */
public enum ModelFailureCategory {
    MODEL_FIRST_TOKEN_TIMEOUT, MODEL_REQUEST_TIMEOUT, MODEL_RATE_LIMITED, MODEL_NETWORK_FAILURE,
    MODEL_REQUEST_TOO_LARGE, MODEL_INVALID_REQUEST, MODEL_CANCELLED, MODEL_REQUEST_FAILED;

    public static ModelFailureCategory classify(Throwable failure) {
        ModelFailureCategory fallback=MODEL_REQUEST_FAILED;
        for(int depth=0;failure!=null&&depth<32;depth++,failure=failure.getCause()) {
            if(failure instanceof RuntimeStreamingChatModel.FirstTokenTimeoutException)return MODEL_FIRST_TOKEN_TIMEOUT;
            if(failure instanceof java.util.concurrent.CancellationException)return MODEL_CANCELLED;
            if(failure instanceof dev.langchain4j.exception.RateLimitException)return MODEL_RATE_LIMITED;
            if(failure instanceof dev.langchain4j.exception.HttpException http) {
                if(http.statusCode()==429)return MODEL_RATE_LIMITED;
                if(http.statusCode()==413)return MODEL_REQUEST_TOO_LARGE;
                if(http.statusCode()>=400 && http.statusCode()<500)fallback=MODEL_INVALID_REQUEST;
            }
            if(failure instanceof java.net.http.HttpConnectTimeoutException || failure instanceof java.net.ConnectException
                || failure instanceof java.net.UnknownHostException || failure instanceof java.net.SocketException)
                return MODEL_NETWORK_FAILURE;
            if(failure instanceof java.net.http.HttpTimeoutException || failure instanceof java.net.SocketTimeoutException
                || failure instanceof java.util.concurrent.TimeoutException || failure instanceof dev.langchain4j.exception.TimeoutException)
                fallback=MODEL_REQUEST_TIMEOUT;
            else if(failure instanceof dev.langchain4j.exception.InvalidRequestException)fallback=MODEL_INVALID_REQUEST;
        }
        return fallback;
    }
}
