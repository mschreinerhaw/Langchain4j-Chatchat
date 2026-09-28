package com.chatchat.api.runtime;
import com.chatchat.runtime.skill.api.execution.*;
import com.chatchat.runtime.skill.api.skill.*;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
class SkillMetricOperatorTest {
    private final SkillMetricOperator operator=new SkillMetricOperator(new VerifiedEvidenceComputationOperator(new ObjectMapper()),new ObjectMapper());
    @Test void computesExactDecimalsAndRetainsSourceEvidence() {
        var result=operator.execute(new SkillAnalysisStep("total","SUM","rows","value",List.of()),data("t"),
            new SkillRoleContext("t","u",List.of(),List.of(),Map.of()));
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(new BigDecimal(result.output().get("value").toString())).isEqualByComparingTo("1234567890.123456790");
        assertThat(result.evidenceIds()).contains("source");
    }
    @Test void refusesCrossTenantEvidence() {
        assertThat(operator.execute(new SkillAnalysisStep("total","SUM","rows","value",List.of()),data("other"),
            new SkillRoleContext("t","u",List.of(),List.of(),Map.of())).observations()).contains("SOURCE_TENANT_MISMATCH");
    }
    private SkillDataResult data(String tenant) {
        return new SkillDataResult(new SkillDataRequirement("rows","sales.rows.v1",List.of(),false,Map.of()),
            SkillDataResult.Status.AVAILABLE,List.of(Map.of("value",new BigDecimal("1234567890.123456789")),
                Map.of("value",new BigDecimal("0.000000001"))),Map.of("tenantId",tenant,"evidenceId","source"),List.of());
    }
}

