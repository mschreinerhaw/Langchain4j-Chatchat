package com.chatchat.api.config;

import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Applies correlation and cache-safety guarantees to the public JSON contract. */
@RestControllerAdvice
public class ApiResponseContractAdvice implements ResponseBodyAdvice<Object> {

    private static final String PUBLISHED_AGENT_API_PREFIX = "/api/v1/published-agents/";

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return body;
        }
        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        if (body instanceof ApiResponse<?> apiResponse && apiResponse.getRequestId() == null) {
            Object requestId = httpRequest.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
            if (requestId != null) {
                apiResponse.setRequestId(String.valueOf(requestId));
            }
        }
        if (httpRequest.getRequestURI().startsWith(PUBLISHED_AGENT_API_PREFIX)) {
            response.getHeaders().setCacheControl(CacheControl.noStore());
            response.getHeaders().setPragma("no-cache");
        }
        return body;
    }
}
