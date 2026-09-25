import { describe, expect, it } from "vitest";
import DomainIntelligenceView from "./DomainIntelligenceView.js";

describe("domain intelligence analysis composer", () => {
  it("sends only selected knowledge scope, bounded tool calls and analysis instruction", () => {
    const form = {
      providerId: "group.analysis", capability: "finance.analysis.v1", skillId: "investment-skill",
      query: "Analyze selected evidence", documentIds: ["doc-1"], documentTags: [],
      selectedTools: ["position_read"], toolArguments: { position_read: '{"customerId":"c-1"}' },
      dataTemplateId: "", dataAssetName: "", dataEnvironment: "", dataParameters: "{}",
      confirmRemoteTransfer: true
    };
    const request = DomainIntelligenceView.methods.buildRequest.call({ form,
      selectedProvider: { evidenceTypes: ["DocumentAnalysisEvidence", "ToolAnalysisEvidence"] } });
    expect(request).toMatchObject({
      providerId: "group.analysis", skillId: "investment-skill", documentIds: ["doc-1"],
      tools: [{ toolName: "position_read", arguments: { customerId: "c-1" } }]
    });
    expect(request).not.toHaveProperty("agentExecutionMode");
  });

  it("rejects invalid tool arguments before dispatch", () => {
    const form = {
      providerId: "group.analysis", capability: "finance.analysis.v1", skillId: "investment-skill",
      query: "Analyze", documentIds: [], documentTags: [], selectedTools: ["position_read"],
      toolArguments: { position_read: "[1,2]" }, dataTemplateId: "", dataAssetName: "",
      dataEnvironment: "", dataParameters: "{}", confirmRemoteTransfer: true
    };
    expect(() => DomainIntelligenceView.methods.buildRequest.call({ form,
      selectedProvider: { evidenceTypes: ["ToolAnalysisEvidence"] } })).toThrow(/JSON 对象/);
  });
});
