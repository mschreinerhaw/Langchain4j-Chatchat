package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.orchestration.analysis.contract.AnalysisContextPresentationContract;
import com.chatchat.agents.orchestration.analysis.insight.SemanticInsightRecipeCatalog;


import com.chatchat.agents.protocol.AnswerContract;
import com.chatchat.agents.orchestration.answer.AnswerContractCompiler;
import com.chatchat.agents.orchestration.answer.AnswerCriticRepairer;
import com.chatchat.agents.orchestration.evidence.EvidenceSufficiencyGate;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerQualityBusinessNeutralityTest {

    private static final List<String> QUALITY_CORE_FILES = List.of(
        "protocol/AnswerContract.java",
        "orchestration/answer/AnswerContractCompiler.java",
        "orchestration/evidence/EvidenceSufficiencyGate.java",
        "orchestration/answer/AnswerCriticRepairer.java",
        "orchestration/analysis/contract/AnalysisContextPresentationContract.java",
        "runtime/context/AgentRoleAnalysisContext.java",
        "orchestration/analysis/insight/SemanticInsightRecipeCatalog.java",
        "orchestration/analysis/summary/AnalysisSummaryGovernanceBridge.java",
        "orchestration/analysis/summary/GovernedRecordFinalPromptBuilder.java",
        "orchestration/analysis/summary/GovernedFinalClaimContract.java"
    );

    private static final List<String> FORBIDDEN_BUSINESS_LITERALS = List.of(
        "finance", "financial", "banking", "securities",
        "document qa", "oracle", "mysql", "postgresql",
        "customer_id", "account_id", "web_search",
        "金融", "证券", "银行", "客户号", "数据库", "运维"
    );

    @Test
    void answerQualityCoreContainsNoDomainOrConcreteToolBranches() throws IOException {
        Path root = sourceRoot();
        for (String file : QUALITY_CORE_FILES) {
            Path sourceFile = root.resolve(file);
            String source = Files.readString(sourceFile).toLowerCase(Locale.ROOT);
            assertThat(FORBIDDEN_BUSINESS_LITERALS)
                .as("Answer quality core must be driven by runtime contracts: " + file)
                .noneMatch(source::contains);
        }
    }

    private Path sourceRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path module = current.resolve("chatchat-agents");
        Path root = Files.isDirectory(module) ? module : current;
        Path source = root.resolve("src/main/java/com/chatchat/agents");
        assertThat(source).isDirectory();
        return source;
    }
}
