package com.chatchat.agents.runtime.toolcall;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RequiredTemporalArgumentGroundingTest {
    private final RequiredTemporalArgumentGrounding guard = new RequiredTemporalArgumentGrounding(
        Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
    private final Map<String,Object> schema = Map.of("properties", Map.of("period", Map.of(
        "type", "string", "format", "date")), "required", List.of("period"));

    @Test void rejectsInventedDatesBeforeScalarAndBatchExecution() {
        for (Map<String,Object> input : List.of(Map.<String,Object>of("period","1890-01-01"),
            Map.<String,Object>of("calls",List.of(Map.of("arguments",Map.of("period","1890-01-01")))))) {
            assertThatThrownBy(() -> guard.validate(input,schema,"分析仓储情况",Map.of()))
                .hasMessageContaining("USER_INPUT_REQUIRED").hasMessageContaining("period");
        }
    }
    @Test void acceptsUserDatesCalendarPeriodsAndDeclaredDefault() {
        for (String query : List.of("2025年分析", "during 2025", "2025-07期间", "2025年7月", "2025年7月1日", "2025-07-01"))
            assertThatCode(() -> guard.validate(Map.of("period","2025-07-01"),schema,query,Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(Map.of("period","2025-07-01"),
            Map.of("properties",Map.of("period",Map.of("format","date","default","2025-07-01")),"required",List.of("period")),
            "分析",Map.of())).doesNotThrowAnyException();
    }
    @Test void identifiersAndDateExamplesAreNotUserPeriodEvidence() {
        for (String query : List.of("仓库2025", "2025-08期间", "2025年8月"))
            assertThatThrownBy(() -> guard.validate(Map.of("period","2025-07-01"),schema,query,
                Map.of(1,Map.of("schema",Map.of("rows",List.of(Map.of("period","2025-07-01")))))))
                .hasMessageContaining("USER_INPUT_REQUIRED");
    }
    @Test void acceptsActualCompletedRecordButRejectsFailedDataAndParameterEcho() {
        var actual = Map.of("success",true,"data",Map.of("rows",List.of(Map.of("period","2025-07-01"))));
        assertThatCode(() -> guard.validate(Map.of("period","2025-07-01"),schema,"分析快照",Map.of(1,actual))).doesNotThrowAnyException();
        for(Object output : List.of(Map.of("success",false,"data",actual),Map.of("arguments",Map.of("period","2025-07-01"))))
            assertThatThrownBy(() -> guard.validate(Map.of("period","2025-07-01"),schema,"分析快照",Map.of(1,output)))
                .hasMessageContaining("USER_INPUT_REQUIRED");
    }
    @Test void relativePeriodsUseServerClockAndDoNotAuthorizeAnotherYear() {
        assertThatCode(() -> guard.validate(Map.of("period","2025-01-01"),schema,"去年",Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(Map.of("period","2026-10-01"),schema,"上月",Map.of())).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.validate(Map.of("period","2024-01-01"),schema,"去年",Map.of())).hasMessageContaining("USER_INPUT_REQUIRED");
    }
}
