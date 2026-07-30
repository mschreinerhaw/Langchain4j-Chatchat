package com.chatchat.mcpserver.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiBusinessResponseEvaluatorTest {

    @Test
    void acceptsCommonSuccessfulBusinessEnvelopesAndPlainData() {
        assertThat(ApiBusinessResponseEvaluator.failure(Map.of("code", 0, "data", List.of()))).isNull();
        assertThat(ApiBusinessResponseEvaluator.failure(Map.of("code", 200, "data", Map.of()))).isNull();
        assertThat(ApiBusinessResponseEvaluator.failure(Map.of("success", true, "result", "done"))).isNull();
        assertThat(ApiBusinessResponseEvaluator.failure(Map.of("rows", List.of(Map.of("id", 1))))).isNull();
        assertThat(ApiBusinessResponseEvaluator.failure(null)).isNull();
    }

    @Test
    void rejectsBusinessFailureEvenWhenTransportWasSuccessful() {
        assertThat(ApiBusinessResponseEvaluator.failure(
            Map.of("code", -10002, "note", "sessionId无效")))
            .contains("sessionId无效", "code=-10002");
        assertThat(ApiBusinessResponseEvaluator.failure(
            Map.of("code", 500, "message", "服务执行异常")))
            .contains("服务执行异常", "code=500");
        assertThat(ApiBusinessResponseEvaluator.failure(
            Map.of("success", false, "message", "参数校验失败")))
            .isEqualTo("参数校验失败");
        assertThat(ApiBusinessResponseEvaluator.failure(
            Map.of("status", "FAILED", "detail", "数据未返回")))
            .isEqualTo("数据未返回");
        assertThat(ApiBusinessResponseEvaluator.failure(
            Map.of("ok", true, "errors", List.of("downstream unavailable"))))
            .contains("downstream unavailable");
    }
}
