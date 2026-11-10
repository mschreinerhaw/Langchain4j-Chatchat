package com.chatchat.api.runtime.coding.cline;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ClinePythonResultAdmissionTest {
    @Test void analysisAdmitsExplicitCodeAndReportWhileLegacyKeepsItsProfile() {
        var files=List.of("rows.jsonl","analysis.py","report.md");
        assertThatCode(()->ClineCodingAdapter.requirePythonResultFiles(files,true)).doesNotThrowAnyException();
        assertThatThrownBy(()->ClineCodingAdapter.requirePythonResultFiles(files,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->ClineCodingAdapter.requirePythonResultFiles(List.of("rows.jsonl"),false)).doesNotThrowAnyException();
    }
    @Test void analysisStillRequiresPrimaryRowsAndSafeSupportedArtifactNames() {
        for(var files:List.of(List.of("report.md"),List.of("rows.jsonl","../report.md"),List.of("rows.jsonl","run.sh")))
            assertThatThrownBy(()->ClineCodingAdapter.requirePythonResultFiles(files,true)).isInstanceOf(IllegalArgumentException.class);
    }
}
