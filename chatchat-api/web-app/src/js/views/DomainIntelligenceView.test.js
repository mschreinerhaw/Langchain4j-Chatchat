import { describe, expect, it } from "vitest";
import DomainIntelligenceView from "./DomainIntelligenceView.js";

describe("domain intelligence analysis composer", () => {
  it("shows Skill-bound MCP tools for role-governed providers without a registration-time allowlist", () => {
    const selectedSkills = [{ value: "investment-skill", boundMcpToolNames: ["position_read"] }];
    const available = DomainIntelligenceView.computed.availableTools.call({ selectedSkills,
      selectedProvider: { grantRestricted: true, mcpRoleGoverned: true, mcpToolNames: [] } });
    expect(available).toEqual([{ skillId: "investment-skill", toolName: "position_read",
      key: "investment-skill::position_read" }]);
  });

  it("preserves documents and tools by Skill when selecting multiple Skills", () => {
    const form = {
      providerId: "llm:general", capability: "general.analysis.v1", skillId: "skill-a",
      skillIds: ["skill-a", "skill-b"], query: "Compare", documentIds: [],
      documentsBySkill: { "skill-a": ["doc-a"], "skill-b": ["doc-b"] },
      selectedTools: ["skill-b::read_positions"],
      toolArguments: { "skill-b::read_positions": '{"id":"42"}' },
      dataTemplateId: "", dataAssetName: "", dataEnvironment: "", dataParameters: "{}",
      confirmRemoteTransfer: true
    };
    const request = DomainIntelligenceView.methods.buildRequest.call({ form,
      selectedProvider: { evidenceTypes: ["DocumentAnalysisEvidence", "ToolAnalysisEvidence"] } });
    expect(request.skills).toEqual([
      { skillId: "skill-a", documentIds: ["doc-a"] },
      { skillId: "skill-b", documentIds: ["doc-b"] }
    ]);
    expect(request.tools).toEqual([{ skillId: "skill-b", toolName: "read_positions",
      arguments: { id: "42" } }]);
  });
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
