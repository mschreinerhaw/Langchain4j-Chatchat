package com.chatchat.agents.runtime.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRegressionEvaluatorTest {

    private final AgentRegressionCaseLoader loader = new AgentRegressionCaseLoader();
    private final AgentRegressionEvaluator evaluator = new AgentRegressionEvaluator();
    private final AgentDeterministicScorer deterministicScorer = new AgentDeterministicScorer();

    @Test
    void loadsYamlCaseAndDetectsFalseReject() {
        AgentRegressionCase testCase = loadSparkCase();

        AgentRegressionResult result = evaluator.evaluate(testCase, new AgentRegressionObservation(
            testCase.id(),
            List.of(
                "Spark SQL Reference.md JDBC connector CREATE TABLE example for MySQL.",
                "Spark SQL Reference.md filesystem connector supports source and sink connector tables."
            ),
            4,
            0.82D,
            3,
            false,
            true,
            "JDBC and Filesystem appear separately, but there is no complete combined sync SQL example.",
            0.4D,
            1.0D,
            "No answer because review rejected the result."
        ));

        assertThat(result.status()).isEqualTo(AgentRegressionResult.FAIL);
        assertThat(result.retrieval().hit()).isTrue();
        assertThat(result.evidence().score()).isEqualTo(0.82D);
        assertThat(result.review().falseReject()).isTrue();
        assertThat(result.notes()).contains("false_reject_detection");
    }

    @Test
    void passesWhenPartialEvidenceIsAcceptedAndAnswerContainsExpectedContent() throws Exception {
        AgentRegressionCase testCase = loadSparkCase();

        AgentRegressionResult result = evaluator.evaluate(testCase, new AgentRegressionObservation(
            testCase.id(),
            List.of(
                "Spark SQL Reference.md JDBC connector CREATE TABLE example for MySQL.",
                "Spark SQL Reference.md filesystem connector supports source and sink connector tables."
            ),
            4,
            0.82D,
            3,
            true,
            false,
            "Document search returned usable partial evidence for synthesis.",
            0.8D,
            0.0D,
            "Use CREATE TABLE for JDBC and filesystem source, then INSERT INTO mysql_target SELECT * FROM fs_source."
        ));

        AgentRegressionSuiteReport report = evaluator.summarize(List.of(result));
        String json = new ObjectMapper().writeValueAsString(result);

        assertThat(result.status()).isEqualTo(AgentRegressionResult.PASS);
        assertThat(result.evidence().graph().entities()).contains("jdbc", "filesystem");
        assertThat(report.summary().falseRejectRate()).isZero();
        assertThat(report.hotIssues()).isEmpty();
        assertThat(json)
            .contains("\"caseId\":\"spark_fs_jdbc_sync\"")
            .contains("\"status\":\"PASS\"");
    }

    @Test
    void deterministicScorerBuildsConnectorEntityGraph() {
        AgentRegressionCase testCase = loadSparkCase();

        AgentDeterministicScore score = deterministicScorer.score(testCase, new AgentRegressionObservation(
            testCase.id(),
            List.of(
                "Spark SQL Reference.md JDBC connector CREATE TABLE example for MySQL.",
                "Spark SQL Reference.md filesystem connector supports source and sink connector tables."
            ),
            4,
            null,
            3,
            true,
            false,
            null,
            0.8D,
            0.0D,
            "INSERT INTO mysql_target SELECT * FROM fs_source."
        ));

        assertThat(score.score()).isGreaterThan(0.5D);
        assertThat(score.matchedConnectors()).contains("jdbc", "filesystem", "mysql", "spark sql");
        assertThat(score.graph().relations())
            .contains("Spark SQL -> uses -> JDBC", "Spark SQL -> reads -> FileSystem");
    }

    @Test
    void llmEvaluatorReturnsAuxiliarySemanticScores() {
        AgentRegressionCase testCase = loadSparkCase();
        AgentRegressionObservation observation = new AgentRegressionObservation(
            testCase.id(),
            List.of(
                "Spark SQL Reference.md JDBC connector CREATE TABLE example for MySQL.",
                "Spark SQL Reference.md filesystem connector supports source and sink connector tables."
            ),
            4,
            0.82D,
            3,
            false,
            true,
            "No complete combined sync SQL example.",
            0.4D,
            1.0D,
            "No answer because review rejected."
        );
        AgentRegressionResult deterministic = evaluator.evaluate(testCase, observation);
        AgentRegressionLlmEvaluator llmEvaluator = new AgentRegressionLlmEvaluator(new ObjectMapper());

        AgentRegressionSemanticEvaluation semantic = llmEvaluator.evaluate(
            new SemanticQueueChatModel(),
            testCase,
            observation,
            deterministic
        );

        assertThat(semantic.available()).isTrue();
        assertThat(semantic.evidenceScore()).isEqualTo(0.86D);
        assertThat(semantic.falseRejectLikely()).isTrue();
        assertThat(semantic.reason()).contains("partial evidence");
    }

    private AgentRegressionCase loadSparkCase() {
        InputStream stream = getClass().getResourceAsStream("/agent-regression/spark_fs_jdbc_sync.yml");
        assertThat(stream).isNotNull();
        AgentRegressionCase testCase = loader.load(stream);
        assertThat(testCase.id()).isEqualTo("spark_fs_jdbc_sync");
        return testCase;
    }

    private static final class SemanticQueueChatModel implements ChatModel {

        @Override
        public String chat(String message) {
            assertThat(message)
                .contains("auxiliary only")
                .contains("false_reject_likely")
                .contains("Deterministic local result");
            return """
                {
                  "evidence_score": 0.86,
                  "answer_score": 0.2,
                  "review_score": 0.3,
                  "hallucination_risk": 0.1,
                  "false_reject_likely": true,
                  "reason": "The retrieved chunks provide useful partial evidence, but review required a complete final answer."
                }
                """;
        }
    }
}
