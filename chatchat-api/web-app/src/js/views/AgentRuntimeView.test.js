import { describe, expect, it } from "vitest";
import AgentRuntimeView from "./AgentRuntimeView";

describe("AgentRuntimeView production quality", () => {
  it("formats deterministic quality metrics", () => {
    const context = {
      productionQuality: {
        rates: { claimAuditPassRate: 0.925, groundedRate: 0.875, toolSuccessRate: 1 },
        measurements: { averageClaimCoverage: 0.8, p95LatencyMs: 2200 }
      },
      percent: AgentRuntimeView.methods.percent,
      formatDuration: AgentRuntimeView.methods.formatDuration
    };

    const metrics = AgentRuntimeView.computed.qualityMetrics.call(context);

    expect(metrics.map((metric) => metric.value)).toEqual(["92.5%", "80%", "87.5%", "100%", "2.2s"]);
  });

  it("maps failure contracts to customer-readable labels", () => {
    expect(AgentRuntimeView.methods.failureLabel("CRITICAL_CLAIM_UNBOUND"))
      .toBe("关键结论未绑定证据");
    expect(AgentRuntimeView.methods.failureLabel("UNKNOWN_EVIDENCE_REFERENCE"))
      .toBe("引用不存在");
    expect(AgentRuntimeView.methods.failureLabel("CUSTOM_REASON")).toBe("CUSTOM_REASON");
  });

  it("keeps missing coverage distinct from zero coverage", () => {
    expect(AgentRuntimeView.methods.percent(null)).toBe("-");
    expect(AgentRuntimeView.methods.percent(0)).toBe("0%");
    expect(AgentRuntimeView.methods.qualityBarHeight({ averageClaimCoverage: 1.2 })).toBe("100%");
    expect(AgentRuntimeView.methods.qualityBarHeight({ averageClaimCoverage: 0 })).toBe("6%");
    expect(AgentRuntimeView.methods.storedPercent(87.5)).toBe("87.5%");
  });
});
