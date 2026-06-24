package com.chatchat.chat.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskServiceTest {

    @Test
    void cleanDisplayAnswerPreservesSqlCodeFence() {
        String answer = """
            ## JDBC SQL 案例

            ```sql
            CREATE TABLE MyUserTable (
              id BIGINT,
              name STRING
            ) WITH (
              'connector' = 'jdbc',
              'url' = 'jdbc:mysql://localhost:3306/mydatabase'
            );
            ```

            来源：[doc://jdbc#chunk=2]
            """;

        String cleaned = AgentTaskService.cleanDisplayAnswer(answer);

        assertThat(cleaned)
            .contains("```sql")
            .contains("CREATE TABLE MyUserTable")
            .contains("'connector' = 'jdbc'")
            .contains("来源：[doc://jdbc#chunk=2]");
    }

    @Test
    void cleanDisplayAnswerStillRemovesJsonProtocolFence() {
        String answer = """
            ## 结果

            ```json
            {"uiResponse":{"answer":"internal"}}
            ```

            可展示内容
            """;

        String cleaned = AgentTaskService.cleanDisplayAnswer(answer);

        assertThat(cleaned)
            .contains("可展示内容")
            .doesNotContain("uiResponse")
            .doesNotContain("```json");
    }
}
