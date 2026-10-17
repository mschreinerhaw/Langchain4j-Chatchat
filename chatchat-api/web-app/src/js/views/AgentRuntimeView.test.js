import { describe, expect, it } from "vitest";
import AgentRuntimeView from "./AgentRuntimeView";

describe("AgentRuntimeView production quality", () => {
  it('projects Coding SSE observations immediately and deduplicates replay',()=>{
    const context={selectedRunId:'run',timeline:{events:[],observations:[]},streamCursor:0,
      get events(){return this.timeline.events;}};
    const event={runId:'run',eventId:'transport-1',sequence:1,type:'OBSERVATION_RECORDED',
      payload:{contentPreview:'Python program started',metadata:{eventKind:'CODING_TRACE',eventId:'coding-1',taskId:'task'}}};
    AgentRuntimeView.methods.appendEvent.call(context,event);
    expect(context.timeline.observations).toHaveLength(1);
    expect(context.timeline.observations[0].content).toBe('Python program started');
    AgentRuntimeView.methods.appendEvent.call(context,{...event,eventId:'transport-2',sequence:2});
    expect(context.timeline.observations).toHaveLength(1);expect(context.streamCursor).toBe(2);
    AgentRuntimeView.methods.appendEvent.call(context,{...event,runId:'another-run',eventId:'transport-3',sequence:3});
    expect(context.timeline.observations).toHaveLength(1);expect(context.streamCursor).toBe(2);
  });
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
