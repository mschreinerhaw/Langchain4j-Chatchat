package com.chatchat.api.runtime.coding.evidence;

import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.fixture.CodingLedgerFixture;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CodingFinancialResultVerifierTest {
    final CodingFinancialResultVerifier verifier=new CodingFinancialResultVerifier();
    final Map<String,Object> rules=CodingLedgerFixture.validationRules();
    final CodingInputSnapshotService.Snapshot input=new CodingInputSnapshotService.Snapshot("input:version-1","file","input.jsonl","a".repeat(64),3,100,"source:v1");
    List<Map<String,Object>> clean(){return CodingLedgerFixture.cleanRows();}
    CodingEvidenceCollector.Validation check(List<Map<String,Object>> rows){return verifier.verify(null,input,rules,rows);}
    @Test void cleanExactDecimalLedgerHasNoFalsePositiveAndExplicitIndependentRules(){var result=check(clean());assertThat(result.passed()).isTrue();assertThat(result.businessEvaluated()).isTrue();assertThat(result.checks()).containsEntry("businessDataModified",false).containsEntry("faults",List.of());}
    @Test void fieldTypeFaultIsDetectedWithoutCoercion(){var rows=clean();rows.get(0).put("assets",110.0);assertThat((List<Object>)check(rows).checks().get("faults")).contains("FIELD_TYPE");assertThat(rows.get(0).get("assets")).isEqualTo(110.0);}
    @Test void missingCalendarDateIsNotInvented(){var rows=clean();rows.remove(1);assertThat((List<Object>)check(rows).checks().get("faults")).contains("MISSING_TRADING_DATE");assertThat(rows).hasSize(2);}
    @Test void duplicateIsDetectedWithoutDeduplication(){var rows=clean();rows.add(rows.get(2));assertThat((List<Object>)check(rows).checks().get("faults")).contains("DUPLICATE_DATE");assertThat(rows).hasSize(4);}
    @Test void decimalPrecisionFaultIsNotRoundedAway(){var rows=clean();rows.get(0).put("assets","110.001");assertThat((List<Object>)check(rows).checks().get("faults")).contains("DECIMAL_PRECISION");assertThat(rows.get(0).get("assets")).isEqualTo("110.001");}
    @Test void returnAndCashReconciliationFaultIsNotBusinessRepaired(){var rows=clean();rows.get(1).put("netFlow","1.00");assertThat((List<Object>)check(rows).checks().get("faults")).contains("RETURN_RECONCILIATION");assertThat(rows.get(1).get("returnRate")).isEqualTo("0.10000000");}
    @Test void combinedFaultsRemainVisibleAndValidatorDoesNotMutateInputs(){var rows=clean();rows.remove(1);rows.get(0).put("netFlow",1);rows.get(1).put("assets","133.1001");rows.add(new LinkedHashMap<>(rows.get(1)));var before=rows.stream().map(Map::copyOf).toList();var result=check(rows);assertThat(result.passed()).isFalse();assertThat((List<Object>)result.checks().get("faults")).contains("FIELD_TYPE","MISSING_TRADING_DATE","DECIMAL_PRECISION","DUPLICATE_DATE");assertThat(rows).isEqualTo(before);}
    @Test void missingCalendarAndModelChosenRoundingAreNotAcceptedAsRules(){assertThatThrownBy(()->verifier.validateRules(Map.of())).isInstanceOf(IllegalArgumentException.class);var changed=new LinkedHashMap<>(rules);changed.put("roundingMode","MODEL_CHOICE");assertThatThrownBy(()->verifier.validateRules(changed)).isInstanceOf(IllegalArgumentException.class);}
}
