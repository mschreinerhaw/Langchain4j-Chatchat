import { describe, expect, it } from "vitest";

import {
  runtimeObservationIdentity,
  runtimeObservationPresentation
} from "./runtimeObservationPresentation.js";

describe("runtime observation presentation", () => {
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
});
