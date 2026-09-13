import { describe, expect, it } from "vitest";

import {
  runtimeObservationIdentity,
  runtimeObservationPresentation
} from "./runtimeObservationPresentation.js";

describe("runtime observation presentation", () => {
  it("shows Knowledge Skill extraction and application as distinct auditable events", () => {
    const extracted = { metadata: {
      eventKind: "KNOWLEDGE_SKILLS", eventState: "COMPLETED",
      stage: "SKILLS_EXTRACTED", skillCount: 2
    } };
    const applied = { metadata: {
      eventKind: "KNOWLEDGE_SKILLS", eventState: "APPLIED",
      stage: "CONTEXT_APPLIED", applied: true, sourceCount: 3
    } };

    expect(runtimeObservationPresentation(extracted)).toEqual({
      title: "\u63d0\u53d6 Knowledge Skills", toolName: "knowledge_skills", status: "done"
    });
    expect(runtimeObservationPresentation(applied)).toEqual({
      title: "\u5e94\u7528 Knowledge Skills", toolName: "knowledge_skills", status: "done"
    });
    expect(runtimeObservationIdentity(extracted)).toBe("knowledge-skills:SKILLS_EXTRACTED");
    expect(runtimeObservationIdentity(applied)).toBe("knowledge-skills:CONTEXT_APPLIED");
  });

  it("shows an explicit warning when Knowledge Skills were not applied", () => {
    const payload = { metadata: {
      eventKind: "KNOWLEDGE_SKILLS", eventState: "NOT_APPLIED",
      stage: "CONTEXT_APPLIED", applied: false, status: "not_configured"
    } };

    expect(runtimeObservationPresentation(payload)).toEqual({
      title: "Knowledge Skills \u672a\u5e94\u7528", toolName: "knowledge_skills", status: "warning"
    });
  });

  it("renders a typed plan validation failure as a visible warning", () => {
    const payload = {
      metadata: {
        type: "lifecycle",
        stage: "initial",
        eventKind: "DAG_VALIDATION",
        eventState: "FAILED",
        valid: false,
        executable: false
      }
    };

    expect(runtimeObservationPresentation(payload)).toEqual({
      title: "检测到 DAG 漂移",
      toolName: "dag_validation",
      status: "warning"
    });
    expect(runtimeObservationIdentity(payload)).toBe("dag-validation:initial");
  });

  it("keeps older typed lifecycle validation failures visible", () => {
    const payload = {
      contentPreview: "localized text is not used for classification",
      metadata: {
        type: "lifecycle",
        stage: "rewrite",
        valid: false,
        executable: false
      }
    };

    expect(runtimeObservationPresentation(payload)).toEqual({
      title: "执行计划校验失败",
      toolName: "dag_validation",
      status: "warning"
    });
    expect(runtimeObservationIdentity(payload)).toBe("dag-validation:rewrite");
  });

  it("maps model tool-result review start and completion to one observable phase", () => {
    const started = { metadata: {
      eventKind: "MODEL_INFERENCE", eventState: "STARTED",
      modelPhase: "tool_result_review", stepId: "step-2"
    } };
    const completed = { metadata: { ...started.metadata, eventState: "COMPLETED" } };

    expect(runtimeObservationIdentity(started)).toBe("model-inference:tool_result_review:step-2");
    expect(runtimeObservationIdentity(completed)).toBe(runtimeObservationIdentity(started));
    expect(runtimeObservationPresentation(started)).toEqual({
      title: "模型审查工具结果", toolName: "model_inference", status: "active"
    });
    expect(runtimeObservationPresentation(completed).status).toBe("done");
  });

  it("renders lifecycle observations as backend phases instead of tool results", () => {
    const payload = { metadata: {
      type: "lifecycle", lifecyclePhase: "final_synthesis", stage: "initial"
    } };

    expect(runtimeObservationPresentation(payload)).toEqual({
      title: "生成回答", toolName: "agent_lifecycle", status: "active"
    });
    expect(runtimeObservationIdentity(payload)).toBe("lifecycle:final_synthesis:initial");
  });
});
