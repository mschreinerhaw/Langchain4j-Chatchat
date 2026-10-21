import { it, expect } from "vitest";
import { boundMessagesForPersistence } from "./conversationPersistenceBudget";

it("retains the renderable report artifact when inline chart data exceeds the history budget", () => {
  const artifact = {
    contractVersion: "ui_artifact_ref_v1",
    artifactId: "real-report",
    revision: 1,
    catalogVersion: "enterprise_catalog_v1",
    manifestUrl: "/ui-artifacts/real-report",
    renderMode: "markdown",
  };
  const rows = Array.from({ length: 100 }, (_, i) => ({
    label: `股票 ${i}`,
    description: "行情分析数据".repeat(200),
  }));
  const [saved] = boundMessagesForPersistence([
    {
      role: "assistant",
      content: "三只股票分析",
      uiResponse: {
        contractVersion: "ui_response_v1",
        status: "SUCCESS",
        answer: "三只股票分析",
        uiArtifact: artifact,
        visualization: { type: "chart", spec: { dataset: { rows } } },
        visualizationSpec: { dataset: { rows } },
      },
    },
  ]);
  expect(saved.uiResponse.uiArtifact).toEqual(artifact);
  expect(saved.uiResponse.answer).toBe("三只股票分析");
  expect(saved.uiResponse).not.toHaveProperty("persistenceTruncated");
  expect(Buffer.byteLength(JSON.stringify(saved.uiResponse), "utf8")).toBeLessThan(64 * 1024);
});

it("retains literal code provenance when a restored Studio history is saved again", () => {
  const source = "SELECT 'keep : literal' FROM records WHERE day >= :start_date ;";
  const context = {
    taskOrigin: "studio-coding",
    codingTaskProtocol: "runtime_os.coding.v2",
    codingResultFormat: "WORKSPACE_FILES",
  };
  const [saved] = boundMessagesForPersistence([
    {
      id: "message",
      role: "assistant",
      content: source,
      taskId: "coding-task",
      status: "completed",
      memoryContext: context,
    },
  ]);
  expect(saved.content).toBe(source);
  expect(saved.memoryContext).toEqual(context);
  expect(saved.taskId).toBe("coding-task");
});
