# Agent Runtime Regression Tests

This regression layer tests the Agent evidence chain instead of only checking final answer text.

The framework is hybrid by design:

- Local deterministic execution and scoring are the CI hard gate.
- LLM semantic evaluation is optional and advisory.
- LLM scores must never be the only source of pass/fail truth.

## Case DSL

YAML cases live under:

```text
chatchat-agents/src/test/resources/agent-regression/
```

Example:

```yaml
id: spark_fs_jdbc_sync
name: Spark FileSystem + JDBC sync
tags: [spark, jdbc, filesystem, regression]

input:
  query: "Spark SQL FileSystem + JDBC 同步到 MySQL 示例"

expected:
  retrieval:
    mustContain:
      - Spark SQL Reference
      - jdbc
      - filesystem
  evidence:
    minChunks: 2
    minScore: 0.5
    mustContainKeywords:
      - connector
      - jdbc
      - filesystem
  review:
    mustPass: true
    allowPartialEvidence: true
    maxRejectRate: 0.1
    minScore: 0.6
  answer:
    mustContainAny:
      - INSERT INTO
      - DataFrame
      - jdbc.write
```

## Runtime Observation

Tests feed normalized run observations into `AgentRegressionEvaluator`:

```java
new AgentRegressionObservation(
    caseId,
    retrievalTexts,
    chunkCount,
    evidenceScore,
    chunksUsed,
    reviewPassed,
    reviewRejected,
    reviewReason,
    reviewScore,
    reviewRejectRate,
    answer
)
```

False reject detection is triggered when:

```text
retrieval.hit = true
AND evidence.score > 0.5
AND review.reject = true
```

## Deterministic Scoring

`AgentDeterministicScorer` computes local, repeatable evidence metrics:

- keyword coverage
- entity coverage
- connector coverage
- chunk coverage
- evidence graph relations such as `Spark SQL -> uses -> JDBC`

The regression evaluator combines deterministic metrics using:

```text
overallScore = retrieval * 0.3 + evidence * 0.5 + review * 0.2
```

## Optional LLM Evaluation

`AgentRegressionLlmEvaluator` accepts the case, normalized observation, and deterministic result. It asks a model for auxiliary semantic scores only:

```json
{
  "evidence_score": 0.86,
  "answer_score": 0.7,
  "review_score": 0.6,
  "hallucination_risk": 0.1,
  "false_reject_likely": true,
  "reason": "brief analysis"
}
```

Use this layer for diagnosis and reports, not hard CI gating.

## CI Command

```powershell
mvn -pl chatchat-agents -am '-Dtest=AgentRegressionEvaluatorTest,AgentEvaluationServiceTest,AgentOrchestratorTest,InterpretationPlanRuntimeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

The evaluator emits JSON-serializable `AgentRegressionResult` and `AgentRegressionSuiteReport` records, including `falseRejectRate` and hot issue hints.
